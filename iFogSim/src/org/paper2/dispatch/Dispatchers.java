package org.paper2.dispatch;

import java.util.List;
import java.util.Random;

import org.fog.entities.ServiceDiscovery;
import org.fog.entities.Tuple;
import org.paper2.dispatch.DispatchContext.ServingDevice;
import org.paper2.harness.RequestLog;
import org.paper2.workload.LLMTuple;

/**
 * All dispatch policies P1-P7 (DESIGN.md section 4). Placement (which
 * devices host instances) is decided separately; a dispatcher only chooses
 * among devices that service discovery says host llm_service. For P1
 * (cloud-only) and P2/P3 (edge-only) the PLACEMENT restricts the candidate
 * set; the dispatcher then applies its rule within it.
 *
 * Every dispatcher filters candidates through service discovery, so
 * placement and dispatch compose. All are TupleAware to log the decision;
 * only OracleDispatcher reads genTokensOracle() (RQ3 rule).
 */
public final class Dispatchers {

    private Dispatchers() {}

    static abstract class Base implements TupleAwareLoadBalancer {
        final DispatchContext ctx;
        Base(DispatchContext ctx) { this.ctx = ctx; }

        List<ServingDevice> candidates(String microservice, ServiceDiscovery sd) {
            List<Integer> ids = sd.getServiceDiscoveryInfo().get(microservice);
            if (ids == null) return List.of();
            return ids.stream().map(ctx::device).filter(d -> d != null).toList();
        }

        int chooseAndLog(ServingDevice chosen, Tuple tuple) {
            if (chosen == null) return -1;
            if (tuple instanceof LLMTuple t) {
                ctx.charge(chosen.deviceId, t.ctxTokens());
                RequestLog.recordDispatch(t.requestId(), chosen.deviceId, chosen.name);
            }
            return chosen.deviceId;
        }

        /** Least estimated completion time (net + backlog + service), using
         *  only operationally knowable inputs (context tokens, not gen). */
        ServingDevice leastCompletion(List<ServingDevice> cands, long ctxTokens, int site) {
            ServingDevice best = null; double bestScore = Double.MAX_VALUE;
            for (ServingDevice d : cands) {
                double score = ctx.netLatency(site, d)
                        + ctx.outstandingSeconds(d.deviceId)
                        + ctx.estServiceSeconds(ctxTokens, d);
                if (score < bestScore) { best = d; bestScore = score; }
            }
            return best;
        }

        /** Non-operational counterpart of leastCompletion: scores with the
         *  request's TRUE compute demand (ctx + true generated tokens). Used
         *  by Oracle (P7) and SegregatingOracle (P8) only. */
        double trueDemandMI(LLMTuple t) {
            return org.paper2.harness.Calibration.PREFILL_MI_PER_TOKEN * t.ctxTokens()
                    + org.paper2.harness.Calibration.DECODE_MI_PER_TOKEN * t.genTokensOracle();
        }

        ServingDevice leastCompletionTrue(List<ServingDevice> cands, double trueMi, int site) {
            ServingDevice best = null; double bestScore = Double.MAX_VALUE;
            for (ServingDevice d : cands) {
                double score = ctx.netLatency(site, d) + ctx.outstandingSeconds(d.deviceId) + trueMi / d.mips;
                if (score < bestScore) { best = d; bestScore = score; }
            }
            return best;
        }
    }

    /** P1/P2-nearest: prefer own-site device (least-outstanding among local), else nearest by latency. */
    public static class Nearest extends Base {
        public Nearest(DispatchContext ctx) { super(ctx); }
        @Override public int getDeviceId(String ms, ServiceDiscovery sd, Tuple tuple) {
            List<ServingDevice> cands = candidates(ms, sd);
            if (cands.isEmpty()) return -1;
            int site = (tuple instanceof LLMTuple t) ? t.siteId() : -1;
            ServingDevice best = null; double bestLat = Double.MAX_VALUE;
            for (ServingDevice d : cands) {
                double lat = ctx.netLatency(site, d);
                if (lat < bestLat || (lat == bestLat && best != null
                        && ctx.outstandingSeconds(d.deviceId) < ctx.outstandingSeconds(best.deviceId))) {
                    best = d; bestLat = lat;
                }
            }
            return chooseAndLog(best, tuple);
        }
    }

    /** P3: least estimated outstanding work, ties by latency. */
    public static class LeastLoaded extends Base {
        public LeastLoaded(DispatchContext ctx) { super(ctx); }
        @Override public int getDeviceId(String ms, ServiceDiscovery sd, Tuple tuple) {
            List<ServingDevice> cands = candidates(ms, sd);
            if (cands.isEmpty()) return -1;
            int site = (tuple instanceof LLMTuple t) ? t.siteId() : -1;
            ServingDevice best = null; double bestScore = Double.MAX_VALUE;
            for (ServingDevice d : cands) {
                double score = ctx.outstandingSeconds(d.deviceId) * 1000 + ctx.netLatency(site, d);
                if (score < bestScore) { best = d; bestScore = score; }
            }
            return chooseAndLog(best, tuple);
        }
    }

    /** P4: uniform random (seeded) — floor reference. */
    public static class RandomDispatch extends Base {
        private final Random rng;
        public RandomDispatch(DispatchContext ctx, long seed) { super(ctx); this.rng = new Random(seed); }
        @Override public int getDeviceId(String ms, ServiceDiscovery sd, Tuple tuple) {
            List<ServingDevice> cands = candidates(ms, sd);
            if (cands.isEmpty()) return -1;
            return chooseAndLog(cands.get(rng.nextInt(cands.size())), tuple);
        }
    }

    /** P5: context threshold — small context to own-site edge, large to cloud (threshold tuned on tuning window). */
    public static class Threshold extends Base {
        private final long ctxThreshold;
        public Threshold(DispatchContext ctx, long ctxThreshold) { super(ctx); this.ctxThreshold = ctxThreshold; }
        @Override public int getDeviceId(String ms, ServiceDiscovery sd, Tuple tuple) {
            List<ServingDevice> cands = candidates(ms, sd);
            if (cands.isEmpty()) return -1;
            long c = (tuple instanceof LLMTuple t) ? t.ctxTokens() : 0;
            int site = (tuple instanceof LLMTuple t) ? t.siteId() : -1;
            boolean wantCloud = c > ctxThreshold;
            ServingDevice best = null; double bestScore = Double.MAX_VALUE;
            for (ServingDevice d : cands) {
                boolean isCloud = d.siteId == -1;
                if (isCloud != wantCloud) continue;
                double score = ctx.outstandingSeconds(d.deviceId) * 1000 + ctx.netLatency(site, d);
                if (score < bestScore) { best = d; bestScore = score; }
            }
            if (best == null) return new LeastLoaded(ctx).getDeviceId(ms, sd, tuple); // preferred tier absent
            return chooseAndLog(best, tuple);
        }
    }

    /**
     * ContextAware least-completion-time WITHOUT size segregation. Retained
     * as an ABLATION (isolates the value of the size gate in P6): score =
     * net latency + estimated queue wait + estimated service time over ALL
     * serving nodes. Its weakness under heavy-tailed load is head-of-line
     * blocking -- large requests land on edge nodes and stall the small
     * requests queued behind them. P6 (SizeSegregatedLCT) adds the gate that
     * fixes exactly this.
     */
    public static class ContextAware extends Base {
        public ContextAware(DispatchContext ctx) { super(ctx); }
        @Override public int getDeviceId(String ms, ServiceDiscovery sd, Tuple tuple) {
            List<ServingDevice> cands = candidates(ms, sd);
            if (cands.isEmpty()) return -1;
            long c = (tuple instanceof LLMTuple t) ? t.ctxTokens() : 0;
            int site = (tuple instanceof LLMTuple t) ? t.siteId() : -1;
            return chooseAndLog(leastCompletion(cands, c, site), tuple);
        }
    }

    /**
     * P6 -- the PROPOSED hybrid: Size-Segregated Least-Completion-Time.
     *
     * Two mechanisms, each independently motivated by a baseline's failure:
     *   1. SIZE GATE (fixes P3/ContextAware head-of-line blocking): requests
     *      whose estimated compute demand exceeds a tuned threshold theta are
     *      restricted to CLOUD nodes, keeping the heavy tail off the shared
     *      edge nodes so small requests there stay fast. Demand is estimated
     *      operationally (context tokens + tuning-mean generated tokens),
     *      never the request's true generated-token count.
     *   2. LOAD-AWARE SELECTION within the allowed tier (fixes P5's static,
     *      load-blind split): among candidates, pick minimum estimated
     *      completion time = net latency + estimated backlog + service. Small
     *      requests may therefore OVERFLOW to cloud when the edge is
     *      congested -- something the fixed threshold policy P5 never does.
     *
     * theta is tuned on the TUNING window only (chronological discipline;
     * see tune_hybrid.py), so evaluation windows never inform it.
     */
    public static class SizeSegregatedLCT extends Base {
        private final double thetaMI;
        public SizeSegregatedLCT(DispatchContext ctx, double thetaMI) {
            super(ctx); this.thetaMI = thetaMI;
        }
        @Override public int getDeviceId(String ms, ServiceDiscovery sd, Tuple tuple) {
            List<ServingDevice> all = candidates(ms, sd);
            if (all.isEmpty()) return -1;
            long c = (tuple instanceof LLMTuple t) ? t.ctxTokens() : 0;
            int site = (tuple instanceof LLMTuple t) ? t.siteId() : -1;

            List<ServingDevice> cands = all;
            if (ctx.estDemandMI(c) > thetaMI) {
                List<ServingDevice> cloud = all.stream().filter(d -> d.siteId == -1).toList();
                if (!cloud.isEmpty()) cands = cloud; // else no cloud in serving set: fall back to all
            }
            return chooseAndLog(leastCompletion(cands, c, site), tuple);
        }
    }

    /**
     * P7: ORACLE — identical scoring to ContextAware but the service-time
     * estimate uses the request's TRUE GeneratedTokens, information that
     * does not exist at dispatch time in any real system. Explicitly
     * non-operational; excluded from the fair comparison set (DESIGN RQ3).
     */
    public static class Oracle extends Base {
        public Oracle(DispatchContext ctx) { super(ctx); }
        @Override public int getDeviceId(String ms, ServiceDiscovery sd, Tuple tuple) {
            List<ServingDevice> cands = candidates(ms, sd);
            if (cands.isEmpty()) return -1;
            if (!(tuple instanceof LLMTuple t)) return -1;
            return chooseAndLog(leastCompletionTrue(cands, trueDemandMI(t), t.siteId()), tuple);
        }
    }

    /**
     * P8: SEGREGATING ORACLE (RQ3-sharpening ablation, not a competing
     * policy). Identical structure to P6 -- a demand gate followed by
     * least-completion-time selection within the allowed tier -- but BOTH
     * the gate test and the completion-time scoring use the request's TRUE
     * generated-token count instead of the tuning-window estimate. Comparing
     * P8 to P6 isolates what perfect size information adds ON TOP OF the
     * segregation structure itself (P6 already has the structure, just with
     * an estimate); comparing P8 to P7 (Oracle, same true info but NO
     * segregation) isolates what the structure adds given equally perfect
     * information. Explicitly non-operational; excluded from the fair
     * comparison set for the same reason as P7.
     */
    public static class SegregatingOracle extends Base {
        private final double thetaMI;
        public SegregatingOracle(DispatchContext ctx, double thetaMI) { super(ctx); this.thetaMI = thetaMI; }
        @Override public int getDeviceId(String ms, ServiceDiscovery sd, Tuple tuple) {
            List<ServingDevice> all = candidates(ms, sd);
            if (all.isEmpty()) return -1;
            if (!(tuple instanceof LLMTuple t)) return -1;
            double trueMi = trueDemandMI(t);
            List<ServingDevice> cands = all;
            if (trueMi > thetaMI) {
                List<ServingDevice> cloud = all.stream().filter(d -> d.siteId == -1).toList();
                if (!cloud.isEmpty()) cands = cloud;
            }
            return chooseAndLog(leastCompletionTrue(cands, trueMi, t.siteId()), tuple);
        }
    }

    /**
     * P9/P10: two-factor decomposition of the P6-vs-P8 comparison
     * (external-review fix, 2026-07-10). P8 changes BOTH the gate's input
     * (estimated -> true demand) AND the within-tier score's input
     * simultaneously, at the SAME numeric theta -- so a difference between
     * P6 and P8 could come from either factor, or from theta no longer
     * matching the true-demand distribution's scale. These two policies
     * change exactly one factor each, holding the other at P6's setting and
     * theta fixed, so P6/P9/P10/P8 together form a full 2x2 factorial:
     *
     *              gate=estimated      gate=true
     *   score=est.  P6                 P10 (GateOnlyOracle)
     *   score=true  P9 (ScoreOnlyOracle) P8
     */
    public static class ScoreOnlyOracle extends Base {
        private final double thetaMI;
        public ScoreOnlyOracle(DispatchContext ctx, double thetaMI) { super(ctx); this.thetaMI = thetaMI; }
        @Override public int getDeviceId(String ms, ServiceDiscovery sd, Tuple tuple) {
            List<ServingDevice> all = candidates(ms, sd);
            if (all.isEmpty()) return -1;
            if (!(tuple instanceof LLMTuple t)) return -1;
            long c = t.ctxTokens();
            List<ServingDevice> cands = all;
            if (ctx.estDemandMI(c) > thetaMI) {           // P6's gate: estimated
                List<ServingDevice> cloud = all.stream().filter(d -> d.siteId == -1).toList();
                if (!cloud.isEmpty()) cands = cloud;
            }
            return chooseAndLog(leastCompletionTrue(cands, trueDemandMI(t), t.siteId()), tuple); // true score
        }
    }

    public static class GateOnlyOracle extends Base {
        private final double thetaMI;
        public GateOnlyOracle(DispatchContext ctx, double thetaMI) { super(ctx); this.thetaMI = thetaMI; }
        @Override public int getDeviceId(String ms, ServiceDiscovery sd, Tuple tuple) {
            List<ServingDevice> all = candidates(ms, sd);
            if (all.isEmpty()) return -1;
            if (!(tuple instanceof LLMTuple t)) return -1;
            long c = t.ctxTokens();
            double trueMi = trueDemandMI(t);
            List<ServingDevice> cands = all;
            if (trueMi > thetaMI) {                        // P8's gate: true
                List<ServingDevice> cloud = all.stream().filter(d -> d.siteId == -1).toList();
                if (!cloud.isEmpty()) cands = cloud;
            }
            return chooseAndLog(leastCompletion(cands, c, t.siteId()), tuple); // P6's score: estimated
        }
    }
}

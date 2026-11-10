package org.paper2.dispatch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cloudbus.cloudsim.core.CloudSim;
import org.paper2.harness.Calibration;

/**
 * Shared, OPERATIONAL knowledge available to every dispatcher: topology
 * facts (which serving devices exist, their class/speed, which site they
 * belong to, network distance from each site) and an estimated-outstanding-
 * work load tracker.
 *
 * LOAD TRACKER HONESTY RULE (IMPLEMENTATION_NOTES.md): estimates use ONLY
 * ContextTokens (known at arrival). Decode cost is estimated with the
 * TUNING-WINDOW MEAN generated-token count (a constant fixed before
 * evaluation, provided by the harness) -- never the request's own true
 * GeneratedTokens. On dispatch, a device's outstanding-work account is
 * charged the ESTIMATED seconds; charges expire as simulated time passes.
 */
public class DispatchContext {

    public static final class ServingDevice {
        public final int deviceId;
        public final String name;
        public final int siteId;          // -1 for cloud
        public final long mips;
        public ServingDevice(int deviceId, String name, int siteId, long mips) {
            this.deviceId = deviceId; this.name = name; this.siteId = siteId; this.mips = mips;
        }
    }

    private final Map<Integer, ServingDevice> byId = new HashMap<>();
    private final Map<Integer, List<ServingDevice>> bySite = new HashMap<>();
    private final List<ServingDevice> all = new ArrayList<>();
    private final double tuningMeanGenTokens; // fixed pre-evaluation constant

    // deviceId -> list of (finishEstimate) charges, lazily pruned
    private final Map<Integer, List<Double>> outstanding = new HashMap<>();

    public DispatchContext(double tuningMeanGenTokens) {
        this.tuningMeanGenTokens = tuningMeanGenTokens;
    }

    public void register(ServingDevice d) {
        byId.put(d.deviceId, d);
        all.add(d);
        bySite.computeIfAbsent(d.siteId, k -> new ArrayList<>()).add(d);
        outstanding.put(d.deviceId, new ArrayList<>());
    }

    public List<ServingDevice> allDevices() { return all; }
    public List<ServingDevice> devicesAtSite(int siteId) {
        return bySite.getOrDefault(siteId, new ArrayList<>());
    }
    public ServingDevice device(int id) { return byId.get(id); }

    /** One-way network latency (s) from a request's site gateway to a serving device. */
    public double netLatency(int fromSiteId, ServingDevice target) {
        if (target.siteId == -1) return Calibration.SITE_TO_CLOUD_S;
        if (target.siteId == fromSiteId) return Calibration.GATEWAY_TO_SITE_NODE_S;
        // other edge site: up to cloud-level router and back down
        return 2 * Calibration.SITE_TO_CLOUD_S + Calibration.GATEWAY_TO_SITE_NODE_S;
    }

    /** Estimated compute demand (MI) from operationally knowable inputs only
     *  (context tokens + the tuning-window mean generated-token count). */
    public double estDemandMI(long ctxTokens) {
        return Calibration.PREFILL_MI_PER_TOKEN * ctxTokens
                + Calibration.DECODE_MI_PER_TOKEN * tuningMeanGenTokens;
    }

    /** Estimated service seconds using ONLY operationally knowable inputs. */
    public double estServiceSeconds(long ctxTokens, ServingDevice d) {
        return estDemandMI(ctxTokens) / d.mips;
    }

    /** Estimated outstanding work (s) currently charged to a device. */
    public double outstandingSeconds(int deviceId) {
        double now = CloudSim.clock();
        List<Double> charges = outstanding.get(deviceId);
        charges.removeIf(finish -> finish <= now);
        double sum = 0;
        for (double finish : charges) sum += (finish - now);
        return sum;
    }

    /** Charge a dispatch decision to the chosen device's account. */
    public void charge(int deviceId, long ctxTokens) {
        double now = CloudSim.clock();
        ServingDevice d = byId.get(deviceId);
        double backlog = outstandingSeconds(deviceId);
        outstanding.get(deviceId).add(now + backlog + estServiceSeconds(ctxTokens, d));
    }
}

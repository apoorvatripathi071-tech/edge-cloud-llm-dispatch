package org.paper2.harness;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.core.CloudSim;
import org.fog.application.AppEdge;
import org.fog.application.AppLoop;
import org.fog.application.Application;
import org.fog.entities.FogBroker;
import org.fog.entities.FogDevice;
import org.fog.entities.MicroserviceFogDevice;
import org.fog.entities.PlacementRequest;
import org.fog.entities.Sensor;
import org.fog.entities.Tuple;
import org.fog.utils.Config;
import org.fog.utils.TimeKeeper;
import org.paper2.dispatch.DispatchContext;
import org.paper2.dispatch.Dispatchers;
import org.paper2.dispatch.TupleAwareLoadBalancer;
import org.paper2.placement.StaticPlacementLogic;
import org.paper2.topology.Paper2Topology;
import org.paper2.workload.LLMTraceSensor;
import org.paper2.workload.TraceDistribution;

/**
 * Runs one (prepared-workload, topology, policy, seed) configuration and
 * emits one results JSON (DESIGN.md sections 4-7).
 *
 * Usage:
 *   java org.paper2.harness.ExperimentRunner \
 *     --prepared ../data/prepared/conv_eval1_load1.0_seed42 \
 *     --topology T2 --policy P3 --seed 42 --mean-gen 117 \
 *     --out ../results/run_conv_eval1_load1.0_T2_P3_seed42.json \
 *     [--alpha-scale 1.0] [--beta-scale 1.0] [--threshold 2048] \
 *     [--placement-devices name1,name2,...]   (P6/P7: GA output)
 */
public class ExperimentRunner {

    static final String MODULE = "llm_service";
    static final String TUPLE_TYPE = "LLM_REQUEST";

    public static void main(String[] args) throws Exception {
        Map<String, String> a = parseArgs(args);
        Path prepared = Path.of(a.get("prepared"));
        String topology = a.getOrDefault("topology", "T2");
        String policy = a.get("policy");
        long seed = Long.parseLong(a.getOrDefault("seed", "42"));
        double meanGen = Double.parseDouble(a.getOrDefault("mean-gen", "117"));
        String out = a.get("out");

        Calibration.scale(Double.parseDouble(a.getOrDefault("alpha-scale", "1.0")),
                Double.parseDouble(a.getOrDefault("beta-scale", "1.0")));

        if (!"true".equals(System.getProperty("paper2.debug"))) Log.disable();
        CloudSim.init(1, Calendar.getInstance(), false);
        FogBroker broker = new FogBroker("broker");

        Application app = createApplication("llm_app", broker.getId());
        List<Application> applications = new ArrayList<>();
        applications.add(app);

        Paper2Topology.Built topo = Paper2Topology.build(topology, meanGen);
        DispatchContext dctx = topo.dispatchContext;

        // --- workload: one sensor per site, from prepared CSVs ---
        List<Sensor> sensors = new ArrayList<>();
        double maxSpan = 0;
        long ridBase = 0;
        for (int s = 0; s < topo.siteGateways.size(); s++) {
            Path csv = prepared.resolve("site" + s + ".csv");
            List<String> lines = Files.readAllLines(csv);
            int n = lines.size() - 1;
            double[] arrivals = new double[n];
            long[] ctx = new long[n], gen = new long[n], rids = new long[n];
            for (int i = 1; i <= n; i++) {
                String[] p = lines.get(i).split(",");
                arrivals[i - 1] = Double.parseDouble(p[0]);
                ctx[i - 1] = (long) Double.parseDouble(p[1]);
                gen[i - 1] = (long) Double.parseDouble(p[2]);
                rids[i - 1] = ridBase + (i - 1);
            }
            ridBase += n;
            if (n > 0) maxSpan = Math.max(maxSpan, arrivals[n - 1]);
            double[] gaps = new double[n];
            if (n > 0) gaps[0] = Math.max(arrivals[0], 1e-6);
            for (int i = 1; i < n; i++) gaps[i] = arrivals[i] - arrivals[i - 1];

            LLMTraceSensor sensor = new LLMTraceSensor("sensor-site" + s, TUPLE_TYPE,
                    broker.getId(), app.getAppId(), new TraceDistribution(gaps), s, ctx, gen, rids);
            sensor.setApp(app);
            sensor.setGatewayDeviceId(topo.siteGateways.get(s).getId());
            sensor.setLatency(Calibration.CLIENT_TO_GATEWAY_S);
            sensors.add(sensor);
        }
        // Bounded drain: a request still unfinished 600 s after the last
        // arrival is an SLA violation, not something to wait out (and waiting
        // out deep overload queues is what makes the time-shared scheduler
        // intractable). Unfinished requests are censored at the horizon and
        // scored as violations in analysis. TRANSMISSION_START_DELAY(50)
        // offsets all emits, so include it.
        Config.MAX_SIMULATION_TIME = (int) (maxSpan + 50 + 600);

        // --- placement device set per policy (DESIGN section 4) ---
        List<Integer> serving = new ArrayList<>();
        List<String> servingNames = new ArrayList<>();
        if (a.containsKey("placement-devices")) {
            List<String> names = List.of(a.get("placement-devices").split(","));
            for (FogDevice d : topo.fogDevices)
                if (names.contains(d.getName())) { serving.add(d.getId()); servingNames.add(d.getName()); }
        } else {
            switch (policy) {
                case "P1" -> topo.cloudNodes.forEach(d -> { serving.add(d.getId()); servingNames.add(d.getName()); });
                case "P2", "P3" -> topo.edgeNodes.forEach(d -> { serving.add(d.getId()); servingNames.add(d.getName()); });
                default -> { // P4, P5, and P6/P7 without GA file: all serving nodes
                    topo.cloudNodes.forEach(d -> { serving.add(d.getId()); servingNames.add(d.getName()); });
                    topo.edgeNodes.forEach(d -> { serving.add(d.getId()); servingNames.add(d.getName()); });
                }
            }
        }
        List<Integer> clients = new ArrayList<>();
        for (MicroserviceFogDevice gw : topo.siteGateways) clients.add(gw.getId());

        // --- dispatcher per policy ---
        TupleAwareLoadBalancer lb = switch (policy) {
            case "P1", "P2" -> new Dispatchers.Nearest(dctx);
            case "P3" -> new Dispatchers.LeastLoaded(dctx);
            case "P4" -> new Dispatchers.RandomDispatch(dctx, seed);
            case "P5" -> new Dispatchers.Threshold(dctx,
                    Long.parseLong(a.getOrDefault("threshold", "2048")));
            // P6: proposed hybrid (size-segregated least-completion-time).
            // theta = demand-MI gate, tuned on the tuning window (tune_hybrid.py).
            case "P6" -> new Dispatchers.SizeSegregatedLCT(dctx,
                    Double.parseDouble(a.getOrDefault("theta", "40000")));
            // P6NS: ablation of P6 with the size gate removed (pure
            // least-completion-time), isolating the gate's contribution.
            case "P6NS" -> new Dispatchers.ContextAware(dctx);
            case "P7" -> new Dispatchers.Oracle(dctx);
            // P8: segregating oracle (RQ3 ablation) -- same gate+LCT structure
            // as P6, but both use TRUE generated tokens. Non-operational.
            case "P8" -> new Dispatchers.SegregatingOracle(dctx,
                    Double.parseDouble(a.getOrDefault("theta", "40000")));
            // P9/P10: two-factor decomposition of P6-vs-P8 (external-review
            // fix). Non-operational (both use true generated tokens somewhere).
            case "P9" -> new Dispatchers.ScoreOnlyOracle(dctx,
                    Double.parseDouble(a.getOrDefault("theta", "40000")));
            case "P10" -> new Dispatchers.GateOnlyOracle(dctx,
                    Double.parseDouble(a.getOrDefault("theta", "40000")));
            default -> throw new IllegalArgumentException("unknown policy " + policy);
        };

        // --- FON wiring: root monitors everything; every device's FON = root ---
        Map<Integer, List<FogDevice>> monitored = new HashMap<>();
        monitored.put(topo.root.getId(), topo.fogDevices);
        for (FogDevice d : topo.fogDevices)
            ((MicroserviceFogDevice) d).setFonID(topo.root.getId());
        topo.root.setFonID(topo.root.getId());

        Paper2Controller.LB = lb;
        Paper2Controller.PLACEMENT_LOGIC = new StaticPlacementLogic(MODULE, serving, clients);

        List<Integer> clusterLevels = new ArrayList<>();
        Paper2Controller controller = new Paper2Controller("controller", topo.fogDevices,
                sensors, applications, clusterLevels, 2.0, 1, monitored);

        List<PlacementRequest> prs = new ArrayList<>();
        for (Sensor s : sensors)
            prs.add(new PlacementRequest(app.getAppId(), s.getId(), s.getGatewayDeviceId(),
                    new HashMap<>()));
        controller.submitPlacementRequests(prs, 0);

        String configJson = String.format(
                "{\"prepared\":\"%s\",\"topology\":\"%s\",\"policy\":\"%s\",\"seed\":%d," +
                "\"mean_gen\":%.2f,\"alpha\":%.3f,\"beta\":%.3f," +
                "\"site_to_cloud_s\":%.4f,\"gw_to_node_s\":%.4f,\"client_to_gw_s\":%.4f," +
                "\"serving\":\"%s\"}",
                prepared.getFileName(), topology, policy, seed, meanGen,
                Calibration.PREFILL_MI_PER_TOKEN, Calibration.DECODE_MI_PER_TOKEN,
                Calibration.SITE_TO_CLOUD_S, Calibration.GATEWAY_TO_SITE_NODE_S,
                Calibration.CLIENT_TO_GATEWAY_S, String.join(",", servingNames));
        RequestLog.init(out, configJson);

        TimeKeeper.getInstance().setSimulationStartTime(Calendar.getInstance().getTimeInMillis());
        CloudSim.startSimulation();
        CloudSim.stopSimulation();
        RequestLog.flush();
    }

    static Application createApplication(String appId, int userId) {
        Application app = Application.createApplication(appId, userId);
        app.addAppModule(MODULE, 1024, 1000, 1000);
        app.addAppEdge(TUPLE_TYPE, MODULE, 1000, 500, TUPLE_TYPE, Tuple.UP, AppEdge.SENSOR);
        List<String> loopModules = new ArrayList<>();
        loopModules.add(TUPLE_TYPE);
        loopModules.add(MODULE);
        List<AppLoop> loops = new ArrayList<>();
        loops.add(new AppLoop(loopModules));
        app.setLoops(loops);
        app.createDAG();
        app.setUserId(userId);
        return app;
    }

    static Map<String, String> parseArgs(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                String key = args[i].substring(2);
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) m.put(key, args[++i]);
                else m.put(key, "true");
            }
        }
        return m;
    }
}

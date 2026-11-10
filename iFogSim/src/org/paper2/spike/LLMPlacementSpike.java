package org.paper2.spike;

/*
 * PHASE 1 SPIKE (go/no-go for Angle A -- see ../PLAN.md section 1).
 *
 * Question this file answers: can iFogSim2 represent trace-driven LLM
 * inference requests with PER-REQUEST heterogeneous compute cost (prefill
 * proportional to ContextTokens + decode proportional to GeneratedTokens),
 * injected at the real Azure-trace arrival times?
 *
 * It is NOT an experiment. Numbers printed here calibrate plumbing, not
 * results; MI-per-token constants are placeholders to be calibrated with
 * cited figures (DynamoLLM/Splitwise) in Phase 2.
 *
 * Standard iFogSim sensors emit IDENTICAL tuples (fixed cpu/nw length from
 * the AppEdge) at distribution-timed intervals. The two subclasses below
 * are the whole trick:
 *   - TraceDistribution: returns successive inter-arrival gaps read from
 *     the Azure trace (then a huge sentinel once exhausted, since Sensor
 *     unconditionally reschedules the next emission).
 *   - LLMTraceSensor: overrides transmit() to build each tuple with that
 *     request's own token-derived cpu/nw length.
 */

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.UtilizationModelFull;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.power.PowerHost;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.cloudbus.cloudsim.sdn.overbooking.BwProvisionerOverbooking;
import org.cloudbus.cloudsim.sdn.overbooking.PeProvisionerOverbooking;
import org.fog.application.AppEdge;
import org.fog.application.AppLoop;
import org.fog.application.Application;
import org.fog.application.selectivity.FractionalSelectivity;
import org.fog.entities.Actuator;
import org.fog.entities.FogBroker;
import org.fog.entities.FogDevice;
import org.fog.entities.FogDeviceCharacteristics;
import org.fog.entities.Sensor;
import org.fog.entities.Tuple;
import org.fog.placement.Controller;
import org.fog.placement.ModuleMapping;
import org.fog.placement.ModulePlacementMapping;
import org.fog.policy.AppModuleAllocationPolicy;
import org.fog.scheduler.StreamOperatorScheduler;
import org.fog.utils.Config;
import org.fog.utils.FogLinearPowerModel;
import org.fog.utils.FogUtils;
import org.fog.utils.TimeKeeper;
import org.fog.utils.distribution.Distribution;

public class LLMPlacementSpike {

    // Placeholder calibration (Phase 2 will fix these with cited numbers).
    // Sim time unit = seconds. Edge MIPS below = 10_000, so:
    //   prefill: 2 MI/token  -> 1000-token context ~ 0.2 s on edge
    //   decode: 300 MI/token  -> 100 generated tokens ~ 3 s on edge
    static final double PREFILL_MI_PER_TOKEN = 2.0;
    static final double DECODE_MI_PER_TOKEN = 300.0;
    static final double BYTES_PER_TOKEN = 4.0;

    static final int N_REQUESTS = 2000;   // spike slice
    static final int THIN_FACTOR = 10;    // keep every 10th request (single-instance service, no batching model yet)

    static List<FogDevice> fogDevices = new ArrayList<>();

    /** Inter-arrival gaps from the real trace; sentinel once exhausted. */
    static class TraceDistribution extends Distribution {
        private final double[] gaps;
        private int idx = 0;
        TraceDistribution(double[] gaps) { this.gaps = gaps; }
        @Override public double getNextValue() {
            if (idx < gaps.length) return Math.max(gaps[idx++], 1e-6);
            return 1e12; // trace exhausted; never fires again before sim end
        }
        @Override public int getDistributionType() { return Distribution.DETERMINISTIC; }
        @Override public double getMeanInterTransmitTime() {
            double s = 0; for (double g : gaps) s += g; return gaps.length > 0 ? s / gaps.length : 1.0;
        }
    }

    /** Emits one tuple per trace request with token-derived cpu/nw lengths. */
    static class LLMTraceSensor extends Sensor {
        private final long[] contextTokens, generatedTokens;
        private int idx = 0;

        LLMTraceSensor(String name, String tupleType, int userId, String appId,
                       TraceDistribution dist, long[] ctx, long[] gen) {
            super(name, tupleType, userId, appId, dist);
            this.contextTokens = ctx;
            this.generatedTokens = gen;
        }

        @Override public void transmit() {
            if (idx >= contextTokens.length) return;
            long ctx = contextTokens[idx], gen = generatedTokens[idx];
            idx++;

            AppEdge edge = null;
            for (AppEdge e : getApp().getEdges())
                if (e.getSource().equals(getTupleType())) edge = e;

            long cpuLength = (long) (PREFILL_MI_PER_TOKEN * ctx + DECODE_MI_PER_TOKEN * gen);
            long nwLength = (long) (BYTES_PER_TOKEN * ctx);

            Tuple tuple = new Tuple(getAppId(), FogUtils.generateTupleId(), Tuple.UP,
                    cpuLength, 1, nwLength, 3,
                    new UtilizationModelFull(), new UtilizationModelFull(), new UtilizationModelFull());
            tuple.setUserId(getUserId());
            tuple.setTupleType(getTupleType());
            tuple.setDestModuleName(edge.getDestination());
            tuple.setSrcModuleName(getSensorName());
            tuple.setDestinationDeviceId(getGatewayDeviceId());
            tuple.setActualTupleId(updateTimings(getSensorName(), tuple.getDestModuleName()));
            send(getGatewayDeviceId(), getLatency(), org.fog.utils.FogEvents.TUPLE_ARRIVAL, tuple);
        }
    }

    public static void main(String[] args) throws Exception {
        Log.disable();
        CloudSim.init(1, Calendar.getInstance(), false);

        String appId = "llm_spike";
        FogBroker broker = new FogBroker("broker");

        // --- load trace slice ---
        Path csv = Path.of("..", "data", "conv_sample.csv");
        List<String> lines = Files.lines(csv).skip(1).limit((long) N_REQUESTS * THIN_FACTOR).toList();
        List<Double> arrivals = new ArrayList<>();
        List<Long> ctxList = new ArrayList<>(), genList = new ArrayList<>();
        int kept = 0;
        for (int i = 0; i < lines.size(); i += THIN_FACTOR) {
            String[] parts = lines.get(i).split(",");
            OffsetDateTime t = OffsetDateTime.parse(parts[0].replace(" ", "T"));
            arrivals.add((double) t.toInstant().toEpochMilli() / 1000.0);
            ctxList.add(Long.parseLong(parts[1]));
            genList.add(Long.parseLong(parts[2]));
            kept++;
        }
        double[] gaps = new double[kept];
        for (int i = 1; i < kept; i++) gaps[i] = arrivals.get(i) - arrivals.get(i - 1);
        gaps[0] = 0.1;
        long[] ctx = ctxList.stream().mapToLong(Long::longValue).toArray();
        long[] gen = genList.stream().mapToLong(Long::longValue).toArray();
        double traceSpan = arrivals.get(kept - 1) - arrivals.get(0);
        System.out.printf("Loaded %d requests spanning %.0f s of trace time%n", kept, traceSpan);
        Config.MAX_SIMULATION_TIME = (int) (traceSpan + 200);

        // --- topology: cloud -> proxy -> edge gateway ---
        FogDevice cloud = createFogDevice("cloud", 1_000_000, 65536, 10000, 10000, 0, 0.01, 1648, 1332);
        cloud.setParentId(-1);
        FogDevice proxy = createFogDevice("proxy", 20_000, 8192, 10000, 10000, 1, 0.0, 300, 200);
        proxy.setParentId(cloud.getId());
        proxy.setUplinkLatency(80); // edge site to cloud: 80 ms (in sim seconds: 0.08 if unit=s -- placeholder units, Phase 2 fixes)
        FogDevice edge = createFogDevice("edge-gw", 10_000, 4096, 10000, 10000, 2, 0.0, 120, 80);
        edge.setParentId(proxy.getId());
        edge.setUplinkLatency(5);
        fogDevices.addAll(Arrays.asList(cloud, proxy, edge));

        // --- application graph: LLM_REQUEST -> llm_service -> LLM_RESPONSE ---
        Application app = Application.createApplication(appId, broker.getId());
        app.addAppModule("llm_service", 2048);
        app.addAppEdge("LLM_REQUEST", "llm_service", 1000, 500, "LLM_REQUEST", Tuple.UP, AppEdge.SENSOR);
        app.addAppEdge("llm_service", "CLIENT_DISPLAY", 100, 200, "LLM_RESPONSE", Tuple.DOWN, AppEdge.ACTUATOR);
        app.addTupleMapping("llm_service", "LLM_REQUEST", "LLM_RESPONSE", new FractionalSelectivity(1.0));
        app.setLoops(new ArrayList<>(List.of(
                new AppLoop(new ArrayList<>(List.of("LLM_REQUEST", "llm_service", "CLIENT_DISPLAY"))))));

        // --- trace-driven sensor + actuator on the edge gateway ---
        TraceDistribution dist = new TraceDistribution(Arrays.copyOfRange(gaps, 1, gaps.length));
        LLMTraceSensor sensor = new LLMTraceSensor("llm-src", "LLM_REQUEST", broker.getId(), appId, dist, ctx, gen);
        sensor.setGatewayDeviceId(edge.getId());
        sensor.setLatency(2.0);
        Actuator display = new Actuator("client-display", broker.getId(), appId, "CLIENT_DISPLAY");
        display.setGatewayDeviceId(edge.getId());
        display.setLatency(1.0);

        // --- placement: cloud-only (simplest possible for the spike) ---
        ModuleMapping mapping = ModuleMapping.createModuleMapping();
        mapping.addModuleToDevice("llm_service", "cloud");

        Controller controller = new Controller("controller", fogDevices,
                new ArrayList<>(List.of(sensor)), new ArrayList<>(List.of(display)));
        controller.submitApplication(app, 0, new ModulePlacementMapping(fogDevices, app, mapping));

        TimeKeeper.getInstance().setSimulationStartTime(Calendar.getInstance().getTimeInMillis());
        CloudSim.startSimulation();
        CloudSim.stopSimulation();

        System.out.println("SPIKE COMPLETE: trace-driven, per-request-heterogeneous tuples ran end-to-end.");
    }

    private static FogDevice createFogDevice(String name, long mips, int ram, long upBw, long downBw,
                                             int level, double ratePerMips, double busyPower, double idlePower) throws Exception {
        List<Pe> peList = new ArrayList<>();
        peList.add(new Pe(0, new PeProvisionerOverbooking(mips)));
        PowerHost host = new PowerHost(FogUtils.generateEntityId(), new RamProvisionerSimple(ram),
                new BwProvisionerOverbooking(10000), 1_000_000, peList,
                new StreamOperatorScheduler(peList), new FogLinearPowerModel(busyPower, idlePower));
        List<Host> hostList = new ArrayList<>(List.of(host));
        FogDeviceCharacteristics characteristics = new FogDeviceCharacteristics(
                "x86", "Linux", "Xen", host, 10.0, 3.0, 0.05, 0.001, 0.0);
        FogDevice dev = new FogDevice(name, characteristics, new AppModuleAllocationPolicy(hostList),
                new LinkedList<Storage>(), 10, upBw, downBw, 0, ratePerMips);
        dev.setLevel(level);
        return dev;
    }
}

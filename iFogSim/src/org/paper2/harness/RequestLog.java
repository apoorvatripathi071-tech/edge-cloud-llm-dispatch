package org.paper2.harness;

import java.io.FileWriter;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-request record collection -> one JSON per run (DESIGN.md section 6:
 * every manuscript number must trace to a machine-generated results file).
 *
 * Written as a static registry because emission (sensor), dispatch
 * (load balancer) and completion (LLMTuple.setCloudletStatus) happen in
 * unrelated framework call paths. Flushed via BOTH an explicit call at
 * simulation end AND a JVM shutdown hook -- MicroservicesController's
 * STOP_SIMULATION handler calls System.exit(0), so the hook is the
 * guaranteed path.
 */
public final class RequestLog {

    public static final class Rec {
        long requestId; int siteId; long ctx, gen, cpuLength;
        double emitTime = -1, completionTime = -1;
        int dispatchedTo = -1; String dispatchedToName = "";
    }

    private static final Map<Long, Rec> RECORDS = new ConcurrentHashMap<>();
    private static String outputPath = "results/run_unnamed.json";
    private static String configJson = "{}";
    private static volatile boolean flushed = false;

    private RequestLog() {}

    public static void init(String outPath, String configJsonString) {
        RECORDS.clear();
        flushed = false;
        outputPath = outPath;
        configJson = configJsonString;
        Runtime.getRuntime().addShutdownHook(new Thread(RequestLog::flush));
    }

    public static void recordEmit(long rid, int siteId, long ctx, long gen, long cpuLength) {
        Rec r = RECORDS.computeIfAbsent(rid, k -> new Rec());
        r.requestId = rid; r.siteId = siteId; r.ctx = ctx; r.gen = gen;
        r.cpuLength = cpuLength;
        r.emitTime = org.cloudbus.cloudsim.core.CloudSim.clock();
    }

    public static void recordDispatch(long rid, int deviceId, String deviceName) {
        Rec r = RECORDS.get(rid);
        if (r != null) { r.dispatchedTo = deviceId; r.dispatchedToName = deviceName; }
    }

    public static void recordCompletion(long rid, double clock) {
        Rec r = RECORDS.get(rid);
        if (r != null && r.completionTime < 0) r.completionTime = clock;
    }

    public static synchronized void flush() {
        if (flushed) return;
        flushed = true;
        int completed = 0;
        StringBuilder sb = new StringBuilder();
        sb.append("{\n\"config\": ").append(configJson).append(",\n\"requests\": [\n");
        boolean first = true;
        for (Rec r : RECORDS.values()) {
            if (!first) sb.append(",\n");
            first = false;
            if (r.completionTime >= 0) completed++;
            sb.append(String.format(
                    "{\"rid\":%d,\"site\":%d,\"ctx\":%d,\"gen\":%d,\"mi\":%d," +
                    "\"emit\":%.6f,\"done\":%.6f,\"target\":\"%s\"}",
                    r.requestId, r.siteId, r.ctx, r.gen, r.cpuLength,
                    r.emitTime, r.completionTime, r.dispatchedToName));
        }
        sb.append("\n],\n");
        sb.append(String.format("\"n_total\": %d, \"n_completed\": %d\n}\n", RECORDS.size(), completed));
        try (FileWriter w = new FileWriter(outputPath)) {
            w.write(sb.toString());
            System.out.println("RequestLog: wrote " + RECORDS.size() + " records (" + completed
                    + " completed) to " + outputPath);
        } catch (IOException e) {
            System.err.println("RequestLog flush FAILED: " + e);
        }
    }
}

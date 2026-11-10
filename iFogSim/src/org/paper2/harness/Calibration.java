package org.paper2.harness;

/**
 * Calibration constants (DESIGN.md section 2). Values are set by
 * ExperimentRunner from CLI args so the sensitivity ablation (0.5x/2x)
 * needs no recompilation. Time unit everywhere: SECONDS.
 *
 * Anchoring logic (constants pinned with citations at manuscript time):
 * with STRONG_EDGE_MIPS = 10_000 below, decode = 300 MI/token gives
 * 30 ms/token on a strong edge accelerator and ~7.5 ms/token on the
 * cloud-class pool (40_000 MIPS/instance) -- inside published per-token
 * decode latency ranges. Prefill = 2 MI/token gives 0.2 s per 1K context
 * tokens on strong edge.
 */
public final class Calibration {

    public static double PREFILL_MI_PER_TOKEN = 2.0;
    public static double DECODE_MI_PER_TOKEN = 300.0;
    public static double BYTES_PER_TOKEN = 4.0;

    // Device classes (MIPS per hosted instance)
    public static long CLOUD_MIPS = 40_000;
    public static long STRONG_EDGE_MIPS = 10_000;
    public static long WEAK_EDGE_MIPS = 5_000;

    // Memory (MB) available for model instances per device class
    public static int CLOUD_RAM = 262_144;
    public static int STRONG_EDGE_RAM = 32_768;
    public static int WEAK_EDGE_RAM = 16_384;

    // Power model (W): busy/idle per class -- constants of convenience,
    // relative comparisons are what the paper uses.
    public static double CLOUD_BUSY_W = 700, CLOUD_IDLE_W = 250;
    public static double STRONG_EDGE_BUSY_W = 300, STRONG_EDGE_IDLE_W = 90;
    public static double WEAK_EDGE_BUSY_W = 150, WEAK_EDGE_IDLE_W = 50;

    // Network (seconds of one-way latency)
    public static double CLIENT_TO_GATEWAY_S = 0.005;
    public static double GATEWAY_TO_SITE_NODE_S = 0.002;
    public static double SITE_TO_CLOUD_S = 0.060;

    // Link bandwidths (units consistent with tuple nw lengths)
    public static long EDGE_LINK_BW = 1_000_000;
    public static long WAN_LINK_BW = 500_000;

    private Calibration() {}

    public static void scale(double alphaFactor, double betaFactor) {
        PREFILL_MI_PER_TOKEN *= alphaFactor;
        DECODE_MI_PER_TOKEN *= betaFactor;
    }
}

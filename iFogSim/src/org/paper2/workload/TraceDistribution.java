package org.paper2.workload;

import org.fog.utils.distribution.Distribution;

/**
 * Inter-arrival gaps taken directly from the real trace (hardened from the
 * Phase-1 spike). Sensor unconditionally reschedules its next emission, so
 * after the trace is exhausted this returns a sentinel far beyond
 * MAX_SIMULATION_TIME so no further tuple fires.
 */
public class TraceDistribution extends Distribution {

    private final double[] gaps;
    private int idx = 0;

    public TraceDistribution(double[] gaps) {
        this.gaps = gaps;
    }

    @Override
    public double getNextValue() {
        if (idx < gaps.length) return Math.max(gaps[idx++], 1e-6);
        return 1e12;
    }

    @Override
    public int getDistributionType() {
        return Distribution.DETERMINISTIC;
    }

    @Override
    public double getMeanInterTransmitTime() {
        if (gaps.length == 0) return 1.0;
        double s = 0;
        for (double g : gaps) s += g;
        return s / gaps.length;
    }
}

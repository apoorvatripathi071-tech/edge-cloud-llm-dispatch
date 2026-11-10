package org.paper2.workload;

import org.fog.application.AppEdge;
import org.fog.entities.Sensor;
import org.fog.utils.FogEvents;
import org.fog.utils.FogUtils;
import org.paper2.harness.Calibration;
import org.paper2.harness.RequestLog;

/**
 * One sensor per edge site. Emits one LLMTuple per trace request at the
 * request's real (site-assigned) arrival time, with CPU length
 * alpha*ctx + beta*gen (prefill + aggregated decode; DESIGN.md section 2)
 * and network length proportional to the context payload.
 */
public class LLMTraceSensor extends Sensor {

    private final int siteId;
    private final long[] ctx, gen;
    private final long[] requestIds;
    private int idx = 0;

    public LLMTraceSensor(String name, String tupleType, int userId, String appId,
                          TraceDistribution dist, int siteId,
                          long[] ctx, long[] gen, long[] requestIds) {
        super(name, tupleType, userId, appId, dist);
        this.siteId = siteId;
        this.ctx = ctx;
        this.gen = gen;
        this.requestIds = requestIds;
    }

    @Override
    public void transmit() {
        if (idx >= ctx.length) return;
        long c = ctx[idx], g = gen[idx], rid = requestIds[idx];
        idx++;

        AppEdge edge = null;
        for (AppEdge e : getApp().getEdges())
            if (e.getSource().equals(getTupleType())) edge = e;

        long cpuLength = (long) Math.max(1, Calibration.PREFILL_MI_PER_TOKEN * c
                + Calibration.DECODE_MI_PER_TOKEN * g);
        long nwLength = (long) Math.max(1, Calibration.BYTES_PER_TOKEN * c);

        LLMTuple tuple = new LLMTuple(getAppId(), FogUtils.generateTupleId(), LLMTuple.UP,
                cpuLength, 1, nwLength, 3, rid, siteId, c, g);
        tuple.setUserId(getUserId());
        tuple.setTupleType(getTupleType());
        tuple.setDestModuleName(edge.getDestination());
        tuple.setSrcModuleName(getSensorName());
        tuple.setDestinationDeviceId(-1); // dispatch decides (TupleAwareLoadBalancer)
        tuple.setActualTupleId(updateTimings(getSensorName(), tuple.getDestModuleName()));

        RequestLog.recordEmit(rid, siteId, c, g, cpuLength);
        send(getGatewayDeviceId(), getLatency(), FogEvents.TUPLE_ARRIVAL, tuple);
    }
}

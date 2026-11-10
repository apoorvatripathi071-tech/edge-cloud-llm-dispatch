package org.paper2.workload;

import org.cloudbus.cloudsim.Cloudlet.CloudletStatus;
import org.cloudbus.cloudsim.UtilizationModelFull;
import org.cloudbus.cloudsim.core.CloudSim;
import org.fog.entities.Tuple;
import org.paper2.harness.RequestLog;

/**
 * One LLM inference request from the Azure trace, carrying its own
 * request-level attributes through the dispatch path.
 *
 * Completion capture: Tuple extends Cloudlet, and the CloudSim scheduler
 * marks the cloudlet SUCCESS at execution finish via setCloudletStatus.
 * Overriding it here gives exact per-request completion times with zero
 * framework-code duplication.
 *
 * Operational-availability rule (DESIGN.md RQ3): generatedTokens exists on
 * this object because the SIMULATOR must know the true compute demand, but
 * dispatch policies may NOT read it -- except OracleDispatcher (P7), which
 * is explicitly labeled non-operational. Enforced by code review + the
 * dispatcher API: operational dispatchers receive ctx via ctxTokens()
 * accessor; only the oracle calls genTokensOracle().
 */
public class LLMTuple extends Tuple {

    private final long requestId;
    private final int siteId;
    private final long contextTokens;
    private final long generatedTokens;

    public LLMTuple(String appId, int cloudletId, int direction, long cloudletLength,
                    int pesNumber, long cloudletFileSize, long cloudletOutputSize,
                    long requestId, int siteId, long contextTokens, long generatedTokens) {
        super(appId, cloudletId, direction, cloudletLength, pesNumber, cloudletFileSize,
                cloudletOutputSize, new UtilizationModelFull(), new UtilizationModelFull(),
                new UtilizationModelFull());
        this.requestId = requestId;
        this.siteId = siteId;
        this.contextTokens = contextTokens;
        this.generatedTokens = generatedTokens;
    }

    public long requestId() { return requestId; }
    public int siteId() { return siteId; }
    public long ctxTokens() { return contextTokens; }

    /** ONLY OracleDispatcher (P7) may call this at dispatch time. */
    public long genTokensOracle() { return generatedTokens; }

    /** For RequestLog record-keeping after completion (post-hoc use is legal). */
    public long genTokensForLogging() { return generatedTokens; }

    @Override
    public boolean updateStatus(final CloudletStatus newStatus) {
        boolean changed = super.updateStatus(newStatus);
        // The CloudletScheduler marks completion via updateStatus(SUCCESS)
        // directly (not setCloudletStatus), so the hook must live here.
        if (newStatus == CloudletStatus.SUCCESS) {
            RequestLog.recordCompletion(requestId, CloudSim.clock());
        }
        return changed;
    }
}

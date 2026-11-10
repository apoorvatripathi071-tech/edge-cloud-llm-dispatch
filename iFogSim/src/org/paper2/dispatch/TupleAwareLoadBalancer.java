package org.paper2.dispatch;

import org.fog.entities.LoadBalancer;
import org.fog.entities.ServiceDiscovery;
import org.fog.entities.Tuple;

/**
 * Paper2 extension point: a LoadBalancer that sees the full tuple, enabling
 * request-aware dispatch (context-length-aware, oracle). Wired via the
 * two-line additive extension in ControllerComponent/MicroserviceFogDevice.
 */
public interface TupleAwareLoadBalancer extends LoadBalancer {

    int getDeviceId(String microservice, ServiceDiscovery serviceDiscoveryInfo, Tuple tuple);

    /** Fallback for the stock (name-only) call path. */
    @Override
    default int getDeviceId(String microservice, ServiceDiscovery serviceDiscoveryInfo) {
        return getDeviceId(microservice, serviceDiscoveryInfo, null);
    }
}

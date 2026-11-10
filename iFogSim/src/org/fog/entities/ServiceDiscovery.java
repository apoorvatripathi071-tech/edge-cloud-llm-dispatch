package org.fog.entities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service-discovery table mapping each microservice name to the device ids
 * hosting it. Consulted by load balancers to choose a dispatch target.
 *
 * Paper2: extracted verbatim from ControllerComponent.java and made public
 * (was package-private) so custom dispatch policies in org.paper2.dispatch
 * can accept it as a parameter. Logic unchanged.
 */
public class ServiceDiscovery {
    protected Map<String, List<Integer>> serviceDiscoveryInfo = new HashMap<>();
    int deviceId;

    public ServiceDiscovery(Integer deviceId) {
        this.deviceId = deviceId;
    }

    public void addServiceDIscoveryInfo(String microservice, Integer device) {
        if (serviceDiscoveryInfo.containsKey(microservice)) {
            List<Integer> deviceList = serviceDiscoveryInfo.get(microservice);
            deviceList.add(device);
            serviceDiscoveryInfo.put(microservice, deviceList);
        } else {
            List<Integer> deviceList = new ArrayList<>();
            deviceList.add(device);
            serviceDiscoveryInfo.put(microservice, deviceList);
        }
    }

    public Map<String, List<Integer>> getServiceDiscoveryInfo() {
        return serviceDiscoveryInfo;
    }

    public void removeServiceDIscoveryInfo(String microserviceName, Integer deviceID) {
        if (serviceDiscoveryInfo.containsKey(microserviceName) && serviceDiscoveryInfo.get(microserviceName).contains(new Integer(deviceID))) {
            System.out.println("Service Discovery Info REMOVED (device:" + this.deviceId + ") for microservice :" + microserviceName + " , destDevice : " + deviceID);
            serviceDiscoveryInfo.get(microserviceName).remove(new Integer(deviceID));
            if (serviceDiscoveryInfo.get(microserviceName).size() == 0)
                serviceDiscoveryInfo.remove(microserviceName);
        }
    }
}

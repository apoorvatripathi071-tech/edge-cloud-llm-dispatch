package org.fog.entities;

import org.fog.application.Application;
import org.fog.placement.MicroservicePlacementLogic;
import org.fog.placement.PlacementLogicOutput;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by Samodha Pallewatta on 8/29/2019.
 */
public class ControllerComponent {

    protected LoadBalancer loadBalancer;
    protected MicroservicePlacementLogic microservicePlacementLogic = null;
    protected ServiceDiscovery serviceDiscoveryInfo;

    protected int deviceId;

    public void setDeviceId(int deviceId) {
        this.deviceId = deviceId;
    }


    // Resource Availability Info
    /**
     * Resource Identifiers
     */
    public static final String RAM = "ram";
    public static final String CPU = "cpu";
    public static final String STORAGE = "storage";

    /**
     * DeviceID,<ResourceIdentifier,AvailableResourceAmount>
     */
    protected Map<Integer, Map<String, Double>> resourceAvailability = new HashMap<>();


    //Application Info
    private Map<String, Application> applicationInfo = new HashMap<>();

    //FOg Architecture Info
    private List<FogDevice> fogDeviceList;


    /**
     * For FON
     *
     * @param loadBalancer
     * @param mPlacement
     */
    public ControllerComponent(Integer deviceId, LoadBalancer loadBalancer, MicroservicePlacementLogic mPlacement,
                               Map<Integer, Map<String, Double>> resourceAvailability, Map<String, Application> applicationInfo, List<FogDevice> fogDevices) {
        this.fogDeviceList = fogDevices;
        this.loadBalancer = loadBalancer;
        this.applicationInfo = applicationInfo;
        this.microservicePlacementLogic = mPlacement;
        this.resourceAvailability = resourceAvailability;
        setDeviceId(deviceId);
        serviceDiscoveryInfo = new ServiceDiscovery(deviceId);
    }

    /**
     * For FCN
     *
     * @param loadBalancer
     */
    public ControllerComponent(Integer deviceId, LoadBalancer loadBalancer) {
        this.loadBalancer = loadBalancer;
        setDeviceId(deviceId);
        serviceDiscoveryInfo = new ServiceDiscovery(deviceId);
    }

    /**
     * 1. execute placement logic -> returns the placement mapping.
     * 2. deploy on devices.
     * 3. update service discovery.
     */
    public PlacementLogicOutput executeApplicationPlacementLogic(List<PlacementRequest> placementRequests) {
        if (microservicePlacementLogic != null) {
            PlacementLogicOutput placement = microservicePlacementLogic.run(fogDeviceList, applicationInfo, resourceAvailability, placementRequests);
            return placement;
        }

        return null;
    }

    public void addServiceDiscoveryInfo(String microserviceName, Integer deviceID) {
        this.serviceDiscoveryInfo.addServiceDIscoveryInfo(microserviceName, deviceID);
        System.out.println("Service Discovery Info ADDED (device:" + this.deviceId + ") for microservice :" + microserviceName + " , destDevice : " + deviceID);
    }

    public int getDestinationDeviceId(String destModuleName) {
        return loadBalancer.getDeviceId(destModuleName, serviceDiscoveryInfo);
    }

    /**
     * Paper2 extension (org.paper2): tuple-aware dispatch. If the configured
     * LoadBalancer implements TupleAwareLoadBalancer it receives the full
     * tuple (needed for request-aware policies, e.g. context-length-aware
     * dispatch); otherwise behaves exactly like getDestinationDeviceId(String).
     * Additive and backward compatible: stock load balancers are unaffected.
     */
    public int getDestinationDeviceId(String destModuleName, Tuple tuple) {
        if (loadBalancer instanceof org.paper2.dispatch.TupleAwareLoadBalancer)
            return ((org.paper2.dispatch.TupleAwareLoadBalancer) loadBalancer)
                    .getDeviceId(destModuleName, serviceDiscoveryInfo, tuple);
        return loadBalancer.getDeviceId(destModuleName, serviceDiscoveryInfo);
    }

    public Application getApplicationPerId(String appID) {
        return applicationInfo.get(appID);
    }

    public Double getAvailableResource(int deviceID, String resourceIdentifier) {
        if (resourceAvailability.containsKey(deviceID))
            return resourceAvailability.get(deviceID).get(resourceIdentifier);
        else
            return null;
    }

    public void updateResources(int device, String resourceIdentifier, double remainingResourceAmount) {
        if (resourceAvailability.containsKey(device))
            resourceAvailability.get(device).put(resourceIdentifier, remainingResourceAmount);
        else {
            Map<String, Double> resources = new HashMap<>();
            resources.put(resourceIdentifier, remainingResourceAmount);
            resourceAvailability.put(device, resources);
        }
    }

    public void updateResourceInfo(int deviceId, Map<String, Double> resources) {
        resourceAvailability.put(deviceId, resources);
    }

    public void removeServiceDiscoveryInfo(String microserviceName, Integer deviceID) {
        this.serviceDiscoveryInfo.removeServiceDIscoveryInfo(microserviceName, deviceID);
    }

    public void removeMonitoredDevice(FogDevice fogDevice) {
        this.fogDeviceList.remove(fogDevice);
    }

    public void addMonitoredDevice(FogDevice fogDevice) {
        this.fogDeviceList.add(fogDevice);
    }


}

// Paper2: ServiceDiscovery moved to its own file (ServiceDiscovery.java) and
// made public so dispatch policies in org.paper2.dispatch can accept it as an
// argument. No behavior change.










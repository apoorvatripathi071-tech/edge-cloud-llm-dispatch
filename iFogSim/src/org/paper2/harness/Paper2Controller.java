package org.paper2.harness;

import java.util.List;
import java.util.Map;

import org.fog.application.Application;
import org.fog.entities.FogDevice;
import org.fog.entities.LoadBalancer;
import org.fog.entities.MicroserviceFogDevice;
import org.fog.entities.Sensor;
import org.fog.placement.MicroservicePlacementLogic;
import org.fog.placement.MicroservicesController;

/**
 * MicroservicesController with two injections replacing the hardcoded
 * RRLoadBalancer/PlacementLogicFactory pair:
 *   - the run's dispatch policy (a TupleAwareLoadBalancer) on every device
 *   - the run's StaticPlacementLogic on the FON/CLOUD root
 *
 * Static holders because the superclass constructor calls
 * initializeControllers() before subclass fields would be assigned; the
 * harness sets both holders immediately before constructing this object,
 * single-threaded, one controller per JVM run.
 */
public class Paper2Controller extends MicroservicesController {

    public static LoadBalancer LB;
    public static MicroservicePlacementLogic PLACEMENT_LOGIC;

    public Paper2Controller(String name, List<FogDevice> fogDevices, List<Sensor> sensors,
                            List<Application> applications, List<Integer> clusterLevels,
                            Double clusterLatency, int placementLogic,
                            Map<Integer, List<FogDevice>> monitored) {
        super(name, fogDevices, sensors, applications, clusterLevels, clusterLatency,
                placementLogic, monitored);
    }

    @Override
    protected void initializeControllers(int placementLogic, Map<Integer, List<FogDevice>> monitored) {
        for (FogDevice device : fogDevices) {
            MicroserviceFogDevice cdevice = (MicroserviceFogDevice) device;
            if (cdevice.getDeviceType().equals(MicroserviceFogDevice.FON)
                    || cdevice.getDeviceType().equals(MicroserviceFogDevice.CLOUD)) {
                List<FogDevice> monitoredDevices = monitored.get(cdevice.getFonId());
                cdevice.initializeController(LB, PLACEMENT_LOGIC,
                        getResourceInfo(monitoredDevices), applications, monitoredDevices);
            } else {
                cdevice.initializeController(LB);
            }
        }
    }

    @Override
    protected void initializeControllers(int placementLogic) {
        initializeControllers(placementLogic, null);
    }
}

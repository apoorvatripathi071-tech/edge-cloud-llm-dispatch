package org.paper2.placement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.math3.util.Pair;
import org.fog.application.AppModule;
import org.fog.application.Application;
import org.fog.entities.FogDevice;
import org.fog.entities.PlacementRequest;
import org.fog.placement.MicroservicePlacementLogic;
import org.fog.placement.PlacementLogicOutput;
import org.fog.utils.ModuleLaunchConfig;

/**
 * Deterministic placement from an explicit device list (DESIGN.md section 4:
 * P1/P2/P5 static sets; P6/P7 read a GA-computed set from JSON). The
 * experiment harness resolves device names to ids and passes them here;
 * this class only assembles the PlacementLogicOutput that the FON device
 * distributes (verified against MicroserviceFogDevice's STATIC-mode
 * consumption path).
 */
public class StaticPlacementLogic implements MicroservicePlacementLogic {

    private final String moduleName;
    private final List<Integer> servingDeviceIds;
    private final List<Integer> clientDeviceIds;

    public StaticPlacementLogic(String moduleName, List<Integer> servingDeviceIds,
                                List<Integer> clientDeviceIds) {
        this.moduleName = moduleName;
        this.servingDeviceIds = servingDeviceIds;
        this.clientDeviceIds = clientDeviceIds;
    }

    @Override
    public PlacementLogicOutput run(List<FogDevice> fogDevices, Map<String, Application> applicationInfo,
                                    Map<Integer, Map<String, Double>> resourceAvailability,
                                    List<PlacementRequest> prs) {
        Application app = applicationInfo.values().iterator().next();
        AppModule module = app.getModuleByName(moduleName);

        Map<Integer, Map<Application, List<ModuleLaunchConfig>>> perDevice = new HashMap<>();
        for (int deviceId : servingDeviceIds) {
            List<ModuleLaunchConfig> launches = new ArrayList<>();
            launches.add(new ModuleLaunchConfig(module, 1));
            Map<Application, List<ModuleLaunchConfig>> byApp = new HashMap<>();
            byApp.put(app, launches);
            perDevice.put(deviceId, byApp);
        }

        Map<Integer, List<Pair<String, Integer>>> serviceDiscovery = new HashMap<>();
        for (int clientId : clientDeviceIds) {
            List<Pair<String, Integer>> entries = new ArrayList<>();
            for (int deviceId : servingDeviceIds)
                entries.add(new Pair<>(moduleName, deviceId));
            serviceDiscovery.put(clientId, entries);
        }

        Map<PlacementRequest, Integer> prStatus = new HashMap<>();
        for (PlacementRequest pr : prs) prStatus.put(pr, -1); // all satisfied

        return new PlacementLogicOutput(perDevice, serviceDiscovery, prStatus);
    }

    @Override
    public void updateResources(Map<Integer, Map<String, Double>> resourceAvailability) { }

    @Override
    public void postProcessing() { }
}

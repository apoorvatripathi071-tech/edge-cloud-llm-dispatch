package org.paper2.topology;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.power.PowerHost;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.cloudbus.cloudsim.sdn.overbooking.BwProvisionerOverbooking;
import org.cloudbus.cloudsim.sdn.overbooking.PeProvisionerOverbooking;
import org.fog.entities.FogDevice;
import org.fog.entities.FogDeviceCharacteristics;
import org.fog.entities.MicroserviceFogDevice;
import org.fog.policy.AppModuleAllocationPolicy;
import org.fog.scheduler.StreamOperatorScheduler;
import org.fog.utils.FogLinearPowerModel;
import org.fog.utils.FogUtils;
import org.paper2.dispatch.DispatchContext;
import org.paper2.harness.Calibration;

/**
 * Edge-cloud topologies T1/T2/T3 (DESIGN.md section 3).
 *
 * Root: cloud-router (CLOUD type, also the FON running placement; hosts
 * nothing). Children: N_CLOUD cloud GPU nodes (FCN) at negligible latency,
 * plus one gateway per edge site (CLIENT type; sensors attach here) at
 * SITE_TO_CLOUD_S uplink. Each gateway's children: GPU nodes (FCN),
 * alternating strong/weak classes, at GATEWAY_TO_SITE_NODE_S.
 *
 * Cross-site path: gwA -> root -> gwB -> node, via auto-generated
 * shortest-path routing tables. Device speed realism: with one llm_service
 * instance per serving node, the module executes at the HOST's total MIPS
 * (verified framework behavior), so class differences flow from host MIPS.
 */
public class Paper2Topology {

    public static final class Built {
        public final List<FogDevice> fogDevices = new ArrayList<>();
        public final List<MicroserviceFogDevice> siteGateways = new ArrayList<>();
        public final List<MicroserviceFogDevice> edgeNodes = new ArrayList<>();
        public final List<MicroserviceFogDevice> cloudNodes = new ArrayList<>();
        public MicroserviceFogDevice root;
        public DispatchContext dispatchContext;
    }

    public static Built build(String name, double tuningMeanGenTokens) throws Exception {
        int nSites, nodesPerSite;
        switch (name) {
            case "T1": nSites = 2; nodesPerSite = 2; break;
            case "T2": nSites = 4; nodesPerSite = 4; break;
            case "T3": nSites = 8; nodesPerSite = 4; break;
            default: throw new IllegalArgumentException("unknown topology " + name);
        }
        int nCloudNodes = Math.max(4, nSites);

        Built b = new Built();
        b.dispatchContext = new DispatchContext(tuningMeanGenTokens);

        // Root coordinates placement (must be FON: only FON devices receive
        // PROCESS_PRS in PERIODIC mode) and is named "cloud" so the stock
        // controller's getCloud() cost-reporting call resolves. It hosts no
        // serving module itself.
        b.root = createDevice("cloud", 1000, 8192, 0.001, 200, 150,
                MicroserviceFogDevice.FON, 0);
        b.root.setParentId(-1);
        b.fogDevices.add(b.root);

        for (int i = 0; i < nCloudNodes; i++) {
            MicroserviceFogDevice cn = createDevice("cloud-node-" + i, Calibration.CLOUD_MIPS,
                    Calibration.CLOUD_RAM, 0.01, Calibration.CLOUD_BUSY_W, Calibration.CLOUD_IDLE_W,
                    MicroserviceFogDevice.FCN, 1);
            cn.setParentId(b.root.getId());
            cn.setUplinkLatency(0.001);
            b.cloudNodes.add(cn);
            b.fogDevices.add(cn);
            b.dispatchContext.register(new DispatchContext.ServingDevice(
                    cn.getId(), cn.getName(), -1, Calibration.CLOUD_MIPS));
        }

        for (int s = 0; s < nSites; s++) {
            MicroserviceFogDevice gw = createDevice("site" + s + "-gw", 2000, 4096, 0.0,
                    120, 80, MicroserviceFogDevice.CLIENT, 1);
            gw.setParentId(b.root.getId());
            gw.setUplinkLatency(Calibration.SITE_TO_CLOUD_S);
            b.siteGateways.add(gw);
            b.fogDevices.add(gw);

            for (int k = 0; k < nodesPerSite; k++) {
                boolean strong = (k % 2 == 0);
                long mips = strong ? Calibration.STRONG_EDGE_MIPS : Calibration.WEAK_EDGE_MIPS;
                int ram = strong ? Calibration.STRONG_EDGE_RAM : Calibration.WEAK_EDGE_RAM;
                double busy = strong ? Calibration.STRONG_EDGE_BUSY_W : Calibration.WEAK_EDGE_BUSY_W;
                double idle = strong ? Calibration.STRONG_EDGE_IDLE_W : Calibration.WEAK_EDGE_IDLE_W;
                MicroserviceFogDevice node = createDevice("site" + s + "-node" + k, mips, ram,
                        0.0, busy, idle, MicroserviceFogDevice.FCN, 2);
                node.setParentId(gw.getId());
                node.setUplinkLatency(Calibration.GATEWAY_TO_SITE_NODE_S);
                b.edgeNodes.add(node);
                b.fogDevices.add(node);
                b.dispatchContext.register(new DispatchContext.ServingDevice(
                        node.getId(), node.getName(), s, mips));
            }
        }
        return b;
    }

    private static MicroserviceFogDevice createDevice(String name, long mips, int ram,
            double ratePerMips, double busyPower, double idlePower, String deviceType, int level)
            throws Exception {
        List<Pe> peList = new ArrayList<>();
        peList.add(new Pe(0, new PeProvisionerOverbooking(mips)));
        PowerHost host = new PowerHost(FogUtils.generateEntityId(), new RamProvisionerSimple(ram),
                new BwProvisionerOverbooking(1_000_000), 1_000_000, peList,
                new StreamOperatorScheduler(peList), new FogLinearPowerModel(busyPower, idlePower));
        List<Host> hostList = new ArrayList<>();
        hostList.add(host);
        FogDeviceCharacteristics characteristics = new FogDeviceCharacteristics(
                "x86", "Linux", "Xen", host, 10.0, 3.0, 0.05, 0.001, 0.0);
        MicroserviceFogDevice dev = new MicroserviceFogDevice(name, characteristics,
                new AppModuleAllocationPolicy(hostList), new LinkedList<Storage>(), 10,
                Calibration.WAN_LINK_BW, Calibration.EDGE_LINK_BW, Calibration.EDGE_LINK_BW,
                0, ratePerMips, deviceType);
        dev.setLevel(level);
        return dev;
    }
}

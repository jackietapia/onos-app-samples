/*
 * Copyright 2016 Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onosproject.ecord.carrierethernet.app;

import com.google.common.annotations.Beta;
import com.google.common.collect.ImmutableList;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Service;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.onlab.packet.VlanId;
import org.onlab.util.Bandwidth;
import org.onosproject.net.Link;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.Path;
import org.onosproject.net.config.ConfigFactory;
import org.onosproject.net.config.NetworkConfigEvent;
import org.onosproject.net.config.NetworkConfigListener;
import org.onosproject.net.config.NetworkConfigRegistry;
import org.onosproject.net.config.NetworkConfigService;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.Device;
import org.onosproject.net.topology.PathService;
import org.onosproject.newoptical.api.OpticalConnectivityId;
import org.onosproject.newoptical.api.OpticalPathEvent;
import org.onosproject.newoptical.api.OpticalPathListener;
import org.onosproject.newoptical.api.OpticalPathService;
import org.onosproject.net.topology.TopologyService;
import org.onosproject.net.DefaultLink;
import org.onosproject.net.provider.ProviderId;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.onosproject.net.DefaultEdgeLink.createEdgeLink;
import static org.onosproject.net.config.basics.SubjectFactories.CONNECT_POINT_SUBJECT_FACTORY;
import static org.slf4j.LoggerFactory.getLogger;

@Component(immediate = true)
@Service (value = CarrierEthernetProvisioner.class)
public class CarrierEthernetProvisioner {

    private final Logger log = getLogger(getClass());

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected PathService pathService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected TopologyService topologyService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected CarrierEthernetOpenFlowPacketNodeManager ceOfPktNodeManager;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected OpticalPathService opticalPathService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected NetworkConfigService networkConfigService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected DeviceService deviceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected NetworkConfigRegistry cfgRegistry;

    private final List<ConfigFactory<?, ?>> factories = ImmutableList.of(
            new ConfigFactory<ConnectPoint, PortVlanConfig>(CONNECT_POINT_SUBJECT_FACTORY,
                    PortVlanConfig.class, PortVlanConfig.CONFIG_KEY) {
                @Override
                public PortVlanConfig createConfig() {
                    return new PortVlanConfig();
                }
            });

    // Map of connect points and corresponding VLAN tag
    private final Map<ConnectPoint, VlanId> portVlanMap = new ConcurrentHashMap<>();
    private final Map<ConnectPoint, VlanId> transportVlanMap = new ConcurrentHashMap<>();

    private OpticalPathListener opticalEventListener = new OpticalEventListener();

    private NetworkConfigListener netcfgListener = new InternalNetworkConfigListener();

    private static final int OPTICAL_CONNECT_TIMEOUT_MILLIS = 7000;

    // If set to false, the setup of optical connectivity using the metro app is bypassed
    // TODO: Use the Component Configuration mechanism to set this parameter
    private boolean pktOpticalTopo = false;

    // TODO: Refactor this part
    private final Map<OpticalConnectivityId, OpticalPathEvent.Type> opticalConnectStatusMap = new ConcurrentHashMap<>();

    @Activate
    protected void activate() {
        opticalPathService.addListener(opticalEventListener);
        networkConfigService.addListener(netcfgListener);
        factories.forEach(cfgRegistry::registerConfigFactory);
    }

    @Deactivate
    protected void deactivate() {
        opticalPathService.removeListener(opticalEventListener);
        networkConfigService.removeListener(netcfgListener);
        factories.forEach(cfgRegistry::unregisterConfigFactory);
    }

    public void setupConnectivity(CarrierEthernetForwardingConstruct fc) {

        boolean allPairsConnected = true;

        HashMap<CarrierEthernetNetworkInterface, HashSet<CarrierEthernetNetworkInterface>> ingressEgressNiMap =
                new HashMap<>();

        // Temporary set for iterating through LTP pairs
        Set<CarrierEthernetLogicalTerminationPoint> tempLtpSet = new HashSet<>(fc.ltpSet());

        // Temporary set for indicating which LTPs were finally included
        Set<CarrierEthernetLogicalTerminationPoint> usedLtpSet = new HashSet<>();

        Iterator<CarrierEthernetLogicalTerminationPoint> ltpIt1 = tempLtpSet.iterator();
        while (ltpIt1.hasNext()) {

            CarrierEthernetLogicalTerminationPoint ltp1 = ltpIt1.next();

            // Iterate through all the remaining NIs
            Iterator<CarrierEthernetLogicalTerminationPoint> ltpIt2 = tempLtpSet.iterator();
            while (ltpIt2.hasNext()) {

                CarrierEthernetLogicalTerminationPoint ltp2 = ltpIt2.next();

                // Skip equals
                if (ltp1.equals(ltp2)) {
                    continue;
                }

                // Do not establish connectivity between leaf NIs (applies to Rooted_Multipoint)
                // FIXME: Use proper LTP roles
                if (ltp1.role().equals(CarrierEthernetLogicalTerminationPoint.Role.LEAF)
                        && ltp2.role().equals(CarrierEthernetLogicalTerminationPoint.Role.LEAF)) {
                    continue;
                }

                OpticalConnectivityId opticalConnectId = null;

                if (pktOpticalTopo) {

                    Bandwidth reqBw;

                    if (ltp1.type().equals(CarrierEthernetNetworkInterface.Type.UNI)) {
                        reqBw = ((CarrierEthernetUni) ltp1.ni()).bwp().cir();
                    } else if (ltp2.type().equals(CarrierEthernetNetworkInterface.Type.UNI)) {
                        reqBw = ((CarrierEthernetUni) ltp2.ni()).bwp().cir();
                    } else {
                        reqBw = Bandwidth.bps((double) 0);
                    }

                    opticalConnectId = setupOpticalConnectivity(ltp1.ni().cp(), ltp2.ni().cp(), reqBw, fc.maxLatency());

                    if (opticalConnectId == null ||
                            opticalConnectStatusMap.get(opticalConnectId) != OpticalPathEvent.Type.PATH_INSTALLED) {
                        log.error("Could not establish optical connectivity between {} and {}" +
                                        " (optical id and status: {}, {})",
                                ltp1.ni().cp(), ltp2.ni().cp(), opticalConnectId,
                                (opticalConnectId == null ? "null" : opticalConnectStatusMap.get(opticalConnectId)));
                        allPairsConnected = false;
                        continue;
                    }

                    if (opticalConnectId != null) {
                        fc.setMetroConnectivityId(opticalConnectId);
                        fc.setMetroConnectivityStatus(opticalConnectStatusMap.get(opticalConnectId));
                    }

                    log.info("Metro connectivity id and status for FC {}: {}, {}", fc.id(),
                            fc.metroConnectivity().id(), fc.metroConnectivity().status());

                    if (opticalConnectId != null) {
                        // TODO: find vlanIds for both CO and store to service
                        opticalPathService.getPath(opticalConnectId).ifPresent(links -> {
                            getVlanTag(links).ifPresent(vlan -> {
                                log.info("VLAN ID {} is assigned to CE service {}", vlan, fc.id());
                                fc.setVlanId(vlan);
                            });
                            getTransportVlanTag(links).ifPresent(vlan -> {
                                log.info("Transport VLAN ID {} is assigned to CE service {}", vlan, fc.id());
                                fc.setTransportVlanId(vlan);
                            });
                        });
                    }
                }

                // Update the ingress-egress NI map based on the calculated paths
                if (!updateIngressEgressNiMap(ltp1.ni(), ltp2.ni(), ingressEgressNiMap,
                        fc.congruentPaths(), fc.type())) {
                    removeOpticalConnectivity(opticalConnectId);
                    allPairsConnected = false;
                    continue;
                }

                // Indicate that connection for at least one NI pair has been established
                fc.setState(CarrierEthernetForwardingConstruct.State.ACTIVE);

                // Add NIs to the set of NIs used by the EVC
                usedLtpSet.add(ltp1);
                usedLtpSet.add(ltp2);
            }
            // Remove NI from temporary set so that each pair is visited only once
            ltpIt1.remove();
        }

        // Establish connectivity using the ingressEgressNiMap
        ingressEgressNiMap.keySet().forEach(srcNi -> {
            // Set forwarding only on packet switches
            if (deviceService.getDevice(srcNi.cp().deviceId()).type().equals(Device.Type.SWITCH)) {
                ceOfPktNodeManager.setNodeForwarding(fc, srcNi, ingressEgressNiMap.get(srcNi));
            }
        });

        // Update the NI set, based on the NIs actually used
        fc.setLtpSet(usedLtpSet);

        if (fc.isActive()) {
            if (!allPairsConnected) {
                fc.setState(CarrierEthernetConnection.State.PARTIAL);
            }
        }
    }

    /**
     * Select feasible link paths between two NIs in both directions and update ingressEgressNiMap accordingly
     *
     * @param ni1 the first NI
     * @param ni2 the second NI
     * @param ingressEgressNiMap the method will add here any ingress-egress NI associations
     * @param congruentPaths if true indicates that n1->n2 will follow the same path as n2->n1
     * @return true if the path was updated and false if a path could not be found in any of the directions
     */
    private boolean updateIngressEgressNiMap(CarrierEthernetNetworkInterface ni1, CarrierEthernetNetworkInterface ni2,
                                      HashMap<CarrierEthernetNetworkInterface,
                                              HashSet<CarrierEthernetNetworkInterface>> ingressEgressNiMap,
                                boolean congruentPaths, CarrierEthernetVirtualConnection.Type evcType) {

        // Find the paths for both directions at the same time, so that we can skip the pair if needed
        // TODO: Handle the case when ni1 and ni2 are on the same device - probably in the generateLinkList
        List<Link> forwardLinks = generateLinkList(ni1.cp(), ni2.cp(), evcType);
        List<Link> backwardLinks =
                congruentPaths ? generateInverseLinkList(forwardLinks) : generateLinkList(ni2.cp(), ni1.cp(), evcType);

        // Skip this UNI pair if no feasible path could be found
        if (forwardLinks == null || (backwardLinks == null)) {
            log.warn("There are no feasible paths between {} and {}.",
                    ni1.cp().deviceId(), ni2.cp().deviceId());
            return false;
        }

        // Populate the ingress/egress NI map for the forward and backward paths
        populateIngressEgressNiMap(ni1, ni2, forwardLinks, ingressEgressNiMap);
        populateIngressEgressNiMap(ni2, ni1, backwardLinks, ingressEgressNiMap);

        return true;
    }

    private void populateIngressEgressNiMap(CarrierEthernetNetworkInterface srcNi,
                                            CarrierEthernetNetworkInterface dstNi,
                                            List<Link> linkList,
                                            HashMap<CarrierEthernetNetworkInterface,
                                                    HashSet<CarrierEthernetNetworkInterface>> ingressEgressNiMap
                                            ) {
        // FIXME: Fix the method - avoid generating GENERIC NIs if not needed
        // Add the src and destination NIs as well as the associated Generic NIs
        ingressEgressNiMap.putIfAbsent(srcNi, new HashSet<>());
        // Add last hop entry only if srcNi, dstNi aren't on same device (in which case srcNi, ingressNi would coincide)
        if (!srcNi.cp().deviceId().equals(dstNi.cp().deviceId())) {
            // If srcNi, dstNi are not on the same device, create mappings to/from new GENERIC NIs
            ingressEgressNiMap.get(srcNi).add(new CarrierEthernetGenericNi(linkList.get(1).src(), null));
            CarrierEthernetGenericNi ingressNi =
                    new CarrierEthernetGenericNi(linkList.get(linkList.size() - 2).dst(), null);
            ingressEgressNiMap.putIfAbsent(ingressNi, new HashSet<>());
            ingressEgressNiMap.get(ingressNi).add(dstNi);
        } else {
            // If srcNi, dstNi are on the same device, this is the only mapping that will be created
            ingressEgressNiMap.get(srcNi).add(dstNi);
        }

        // Go through the links and create/add the intermediate NIs
        for (int i = 1; i < linkList.size() - 2; i++) {
            CarrierEthernetGenericNi ingressNi = new CarrierEthernetGenericNi(linkList.get(i).dst(), null);
            ingressEgressNiMap.putIfAbsent(ingressNi, new HashSet<>());
            ingressEgressNiMap.get(ingressNi).add(new CarrierEthernetGenericNi(linkList.get(i + 1).src(), null));
        }
    }

    private List<Link> generateLinkList(ConnectPoint cp1, ConnectPoint cp2,
                                        CarrierEthernetVirtualConnection.Type evcType) {
        Set<Path> paths;
        Path path = null;

        if (!cp1.deviceId().equals(cp2.deviceId())) {
            // If cp1 and cp2 are not on the same device a path must be found
            if (evcType.equals(CarrierEthernetVirtualConnection.Type.POINT_TO_POINT)) {
                // For point-to-point connectivity use pre-calculated paths to make sure the shortest paths are chosen
                paths = pathService.getPaths(cp1.deviceId(), cp2.deviceId());
            } else {
                // Recalculate path so that it's over the pre-calculated spanning tree
                // FIXME: Find a more efficient way (avoid recalculating paths)
                paths = pathService.getPaths(cp1.deviceId(), cp2.deviceId(),
                        new CarrierEthernetSpanningTreeWeight(topologyService));
            }

            // Just select any of the returned paths
            // TODO: Select path in more sophisticated way and return null if any of the constraints cannot be met
            path = paths.iterator().hasNext() ? paths.iterator().next() : null;

            if (path == null) {
                return null;
            }
        }

        List<Link> links = new ArrayList<>();
        links.add(createEdgeLink(cp1, true));
        if (!cp1.deviceId().equals(cp2.deviceId())) {
            links.addAll(path.links());
        }
        links.add(createEdgeLink(cp2, false));

        return links;
    }

    private List<Link> generateInverseLinkList(List<Link> originalLinks) {

        if (originalLinks == null) {
            return null;
        }

        List<Link> inverseLinks = new ArrayList<>();

        inverseLinks.add(createEdgeLink(originalLinks.get(originalLinks.size() - 1).src(), true));

        for (int i = originalLinks.size() - 2 ; i > 0 ; i--) {
            // FIXME: Check again the Link creation parameters
            inverseLinks.add(DefaultLink.builder()
                    .src(originalLinks.get(i).dst())
                    .dst(originalLinks.get(i).src())
                    .type(Link.Type.DIRECT)
                    .providerId(new ProviderId("none", "none"))
                    .build());
        }
        inverseLinks.add(createEdgeLink(originalLinks.get(0).dst(), false));

        return inverseLinks;
    }

    public void removeConnectivity(CarrierEthernetForwardingConstruct fc) {
        // TODO: Add here the same call for all node manager types
        ceOfPktNodeManager.removeAllForwardingResources(fc);
        removeOpticalConnectivity(fc.metroConnectivity().id());
    }

    /**
     * Applies bandwidth profiles to the UNIs of an FC.
     *
     * @param fc the FC representation
     */
    public void applyBandwidthProfiles(CarrierEthernetForwardingConstruct fc) {
        //  TODO: Select node manager depending on device protocol
        fc.uniSet().forEach(uni -> ceOfPktNodeManager.applyBandwidthProfileResources(fc, uni));
    }

    /**
     * Removes bandwidth profiles from the UNIs of an FC.
     *
     * @param fc the FC representation
     */
    public void removeBandwidthProfiles(CarrierEthernetForwardingConstruct fc) {
        //  TODO: Select node manager depending on device protocol
        fc.ltpSet().forEach((ltp -> {
            if (ltp.ni().type().equals(CarrierEthernetNetworkInterface.Type.UNI)) {
                ceOfPktNodeManager.removeBandwidthProfileResources(fc.id(), (CarrierEthernetUni) ltp.ni());
            }
        }));
    }

    private class OpticalEventListener implements OpticalPathListener {

        @Override
        public void event(OpticalPathEvent event) {
            switch (event.type()) {
                case PATH_INSTALLED: case PATH_REMOVED:
                    log.info("Optical path event {} received for {}.", event.type(), event.subject());
                    opticalConnectStatusMap.put(event.subject(), event.type());
                    break;
                default:
                    log.error("Unexpected optical event type.");
                    break;
            }
        }
    }

    private OpticalConnectivityId setupOpticalConnectivity(ConnectPoint ingress, ConnectPoint egress,
                                                           Bandwidth bandwidth, Duration latency) {
        OpticalConnectivityId opticalConnectId = opticalPathService
                .setupConnectivity(ingress, egress, bandwidth, latency);
        if (opticalConnectId != null) {
            long startTime = System.currentTimeMillis();
            while (((System.currentTimeMillis() - startTime) < OPTICAL_CONNECT_TIMEOUT_MILLIS) &&
                    (opticalConnectStatusMap.get(opticalConnectId) != OpticalPathEvent.Type.PATH_INSTALLED)) {
            }
        }
        return opticalConnectId;
    }

    private void removeOpticalConnectivity(OpticalConnectivityId opticalConnectId) {
        if (opticalConnectId != null) {
            opticalPathService.removeConnectivity(opticalConnectId);
            long startTime = System.currentTimeMillis();
            while (((System.currentTimeMillis() - startTime) < OPTICAL_CONNECT_TIMEOUT_MILLIS) &&
                    (opticalConnectStatusMap.get(opticalConnectId) != OpticalPathEvent.Type.PATH_REMOVED)) {
            }
        }
    }

    public void setPktOpticalTopo(boolean pktOpticalTopo) {
        this.pktOpticalTopo = pktOpticalTopo;
    }

    /**
     * Returns VLAN tag assigned to given path.
     * @param links List of links that composes path
     * @return VLAN tag if found any. empty if not found.
     */
    private Optional<VlanId> getVlanTag(List<Link> links) {
        checkNotNull(links);
        Optional<ConnectPoint> edge = links.stream().flatMap(l -> Stream.of(l.src(), l.dst()))
                .filter(portVlanMap::containsKey)
                .findAny();

        if (edge.isPresent()) {
            return Optional.of(portVlanMap.get(edge.get()));
        }

        return Optional.empty();
    }

    /**
     * Returns transport VLAN tag assigned to given path.
     * @param links List of links that composes path
     * @return VLAN transport tag if found any. empty if not found.
     */
    @Beta
    private Optional<VlanId> getTransportVlanTag(List<Link> links) {
        checkNotNull(links);
        return links.stream().flatMap(l -> Stream.of(l.src(), l.dst()))
                .map(transportVlanMap::get)
                .filter(Objects::nonNull)
                .findAny();
    }

    private class InternalNetworkConfigListener implements NetworkConfigListener {

        /**
         * Negative events.
         */
        private final EnumSet<NetworkConfigEvent.Type> negative
            = EnumSet.of(NetworkConfigEvent.Type.CONFIG_UNREGISTERED,
                         NetworkConfigEvent.Type.CONFIG_REMOVED);

        @Override
        public boolean isRelevant(NetworkConfigEvent event) {
            return event.configClass().equals(PortVlanConfig.class);
        }

        @Override
        public void event(NetworkConfigEvent event) {

            ConnectPoint cp = (ConnectPoint) event.subject();
            PortVlanConfig config = networkConfigService.getConfig(cp, PortVlanConfig.class);

            if (config == null) {
                log.info("VLAN tag config is removed from port {}", cp);
                portVlanMap.remove(cp);
                transportVlanMap.remove(cp);
                return;
            }

            if (config.portVlanId().isPresent() && !negative.contains(event.type())) {
                log.info("VLAN tag {} is assigned to port {}", config.portVlanId().get(), cp);
                portVlanMap.put(cp, config.portVlanId().get());
            } else {
                log.info("VLAN tag is removed from port {}", cp);
                portVlanMap.remove(cp);
            }

            if (config.transportVlanId().isPresent() && !negative.contains(event.type())) {
                log.info("transport VLAN tag {} is assigned to port {}",
                         config.transportVlanId().get(), cp);
                transportVlanMap.put(cp, config.transportVlanId().get());
            } else {
                log.info("transport VLAN tag is removed from port {}", cp);
                transportVlanMap.remove(cp);
            }
        }

    }
}

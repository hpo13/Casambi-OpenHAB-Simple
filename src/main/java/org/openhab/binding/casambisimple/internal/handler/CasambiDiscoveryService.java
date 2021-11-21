/**
 * Copyright (c) 2010-2021 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.casambisimple.internal.handler;

import static org.openhab.binding.casambisimple.internal.CasambiBindingConstants.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.casambisimple.internal.driver.CasambiDriverRest;
import org.openhab.binding.casambisimple.internal.driver.messages.CasambiMessageControl;
import org.openhab.binding.casambisimple.internal.driver.messages.CasambiMessageGroup;
import org.openhab.binding.casambisimple.internal.driver.messages.CasambiMessageNetworkState;
import org.openhab.binding.casambisimple.internal.driver.messages.CasambiMessageScene;
import org.openhab.binding.casambisimple.internal.driver.messages.CasambiMessageUnit;
import org.openhab.core.config.discovery.AbstractDiscoveryService;
import org.openhab.core.config.discovery.DiscoveryResult;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link CasambiDiscover} discovers luminaries, switches, scenes and groups on
 * the casambi network
 *
 * @author Hein Osenberg - Initial contribution
 */
@NonNullByDefault
public class CasambiDiscoveryService extends AbstractDiscoveryService implements ThingHandlerService {

    private static final Logger logger = LoggerFactory.getLogger(CasambiDiscoveryService.class);

    public static final Set<ThingTypeUID> SUPPORTED_THING_TYPES = Set.of(THING_TYPE_LUMINARY, THING_TYPE_SCENE);
    private static final int SEARCH_TIME = 10;

    private @Nullable CasambiBridgeHandler bridgeHandler;
    private @Nullable ThingUID bridgeUID;

    // private @Nullable CasambiThingsById luminariesById = null;
    // private @Nullable CasambiThingsById scenesById = null;
    // private @Nullable CasambiThingsById groupsById = null;

    // --- Constructor ---------------------------------------------------------------------------------------------

    public CasambiDiscoveryService() {
        super(SUPPORTED_THING_TYPES, SEARCH_TIME);
        logger.trace("CasambiDiscoveryService: 0 arg constructor.");
    }

    // --- Override superclass methods--------------------------------------------------------------------

    @Override
    public Set<ThingTypeUID> getSupportedThingTypes() {
        return SUPPORTED_THING_TYPES;
    }

    @Override
    protected void startScan() {
        logger.debug("startScan: ");
        scheduleDiscoveryScan();
    }

    @Override
    protected synchronized void stopScan() {
        logger.debug("stopScan: ");
        // FIXME: muss hier ein laufender Scan abgebrochen werden?
        super.stopScan();
    }

    // Discover devices
    public void scheduleDiscoveryScan() {
        logger.trace("scheduleDiscoveryScan:");
        scheduler.execute(new Runnable() {
            @Override
            public void run() {
                doDiscoveryScan();
            }
        });
    }

    private void doDiscoveryScan() {
        logger.trace("doDiscoveryScan: starting runnable.");
        try {
            CasambiBridgeHandler bridgeHandlerLocal = bridgeHandler;
            if (bridgeHandlerLocal != null) {
                CasambiDriverRest casambiRestLocal = bridgeHandlerLocal.casambiRest;
                if (casambiRestLocal != null) {
                    CasambiMessageNetworkState networkState = casambiRestLocal.getNetworkState();
                    if (networkState != null) {

                        // Initialize list of existing things and new units
                        CasambiDiscoverySet oldNewThings = new CasambiDiscoverySet(bridgeHandlerLocal.thingsById);
                        Set<String> oldThings = oldNewThings.getOldThings();
                        logger.debug("doDiscoveryScan: oldThings before scan {}", oldThings);

                        if (networkState.units != null) {
                            // Loop through luminaries removing from existing things and adding to new units
                            for (Entry<Integer, CasambiMessageUnit> unit : networkState.units.entrySet()) {
                                Integer id = unit.getValue().id;
                                Integer fixtureId = unit.getValue().fixtureId;
                                String uid = CasambiLuminaryHandler.getUidFromFixtureId(fixtureId);
                                logger.trace("doDiscoveryScan: got unit id {}, uid {}, name {}", id, uid,
                                        unit.getValue().name);
                                oldNewThings.updateOldNew(uid, id);
                            }
                        }
                        if (networkState.scenes != null) {
                            // Loop through scenes removing from existing things and adding to new units
                            for (Entry<Integer, CasambiMessageScene> scene : networkState.scenes.entrySet()) {
                                Integer id = scene.getValue().id;
                                String uid = CasambiSceneHandler.getUidFromId(id);
                                logger.trace("doDiscoveryScan: got scene id {}, uid {}, name {}", id, uid,
                                        scene.getValue().name);
                                oldNewThings.updateOldNew(uid, id);
                            }
                        }

                        if (networkState.groups != null) {
                            // Loop through scenes removing from existing things and adding to new units
                            for (Entry<Integer, CasambiMessageGroup> group : networkState.groups.entrySet()) {
                                Integer id = group.getValue().id;
                                String uid = CasambiGroupHandler.getUidFromId(id);
                                logger.trace("doDiscoveryScan: got group id {}, uid {}, name {}", id, uid,
                                        group.getValue().name);
                                oldNewThings.updateOldNew(uid, id);
                            }
                        }

                        // Add new units to things (using newThings)
                        Set<String> newThings = oldNewThings.getNewThings();
                        logger.debug("doDiscoveryScan: adding new things {}", newThings);

                        for (String uidId : newThings) {
                            logger.trace("doDiscoveryScan: adding new unit uidId {}", uidId);
                            Integer id = bridgeHandlerLocal.thingsById.getId(uidId);
                            ThingTypeUID type = bridgeHandlerLocal.thingsById.getType(uidId);
                            // FIXME: this will have to add different thing types
                            CasambiMessageNetworkState networkStateLocal = networkState;
                            if (networkStateLocal != null) {
                                if (THING_TYPE_LUMINARY.equals(type) && networkStateLocal.units != null) {
                                    CasambiMessageUnit unit = networkStateLocal.units.get(id);
                                    if (unit != null) {
                                        addDiscoveredLuminary(unit);
                                    }
                                } else if (THING_TYPE_SCENE.equals(type) && networkStateLocal.scenes != null) {
                                    CasambiMessageScene scene = networkStateLocal.scenes.get(id);
                                    if (scene != null) {
                                        addDiscoveredScene(scene);
                                    }
                                } else if (THING_TYPE_GROUP.equals(type) && networkStateLocal.groups != null) {
                                    CasambiMessageGroup scene = networkStateLocal.groups.get(id);
                                    if (scene != null) {
                                        addDiscoveredGroup(scene);
                                    }
                                }
                            }
                        }

                        // Remove things without units (using oldThings)
                        oldThings = oldNewThings.getOldThings();
                        logger.debug("doDiscoveryScan: removing old things {}", oldThings);

                        for (String uidId : oldThings) {
                            logger.trace("doDiscoveryScan: removing old thing uidId {}", uidId);
                            Thing thing = bridgeHandlerLocal.thingsById.getThing(uidId);
                            if (thing != null) {
                                ThingTypeUID type = bridgeHandlerLocal.thingsById.getType(uidId);
                                if (THING_TYPE_LUMINARY.equals(type)) {
                                    CasambiLuminaryHandler handler = (CasambiLuminaryHandler) thing.getHandler();
                                    if (handler != null) {
                                        handler.dispose();
                                    }
                                } else if (THING_TYPE_SCENE.equals(type)) {
                                    CasambiSceneHandler handler = (CasambiSceneHandler) thing.getHandler();
                                    if (handler != null) {
                                        handler.dispose();
                                    }
                                } else if (THING_TYPE_GROUP.equals(type)) {
                                    CasambiGroupHandler handler = (CasambiGroupHandler) thing.getHandler();
                                    if (handler != null) {
                                        handler.dispose();
                                    }
                                }
                            } else {
                                logger.debug("doDiscoveryScan: thing not found, cannot remove for uidId {}", uidId);
                            }
                        }
                    } else {
                        logger.warn(
                                "doDiscoveryScan: no units, scenes or groups in network or discovery not configured.");
                    }
                }
            }
        } catch (Exception e) {
            logger.error("doDiscoveryScan: exception {}. Exiting.", e.getMessage());
            return;
        }
        logger.trace("doDiscoveryScan: done.");
    }

    private void addDiscoveredLuminary(CasambiMessageUnit unit) {
        try {
            if (bridgeUID != null) {
                ThingUID localBridgeUID = bridgeUID;
                String uniqueID = CasambiLuminaryHandler.getUidFromFixtureId(unit.fixtureId);
                ThingUID thingUID = new ThingUID(THING_TYPE_LUMINARY, localBridgeUID, uniqueID);
                logger.debug("addDiscoveredLuminary: tUID: {}, id {}, name {}, uid {}", thingUID, unit.id, unit.name,
                        unit.fixtureId);
                Map<String, Object> properties = new HashMap<>();
                properties.put(LUMINARY_NAME, unit.name);
                properties.put(LUMINARY_ID, unit.id);
                properties.put(LUMINARY_UID, uniqueID);
                if (unit.controls != null) {
                    for (CasambiMessageControl control : unit.controls[0]) {
                        if (control != null) {
                            if (control.isDimmer()) {
                                properties.put(LUMINARY_HAS_DIMMER, true);
                            }
                            if (control.isColor()) {
                                properties.put(LUMINARY_HAS_COLOR, true);
                            }
                            if (control.isCCT()) {
                                properties.put(LUMINARY_HAS_CCT, true);
                                properties.put(LUMINARY_TEMPERATURE_MIN, control.getMin());
                                properties.put(LUMINARY_TEMPERATURE_MAX, control.getMax());
                            }
                            if (control.isColorbalance()) {
                                properties.put(LUMINARY_HAS_COLORBALANCE, true);
                            }
                            if (control.isWhiteDimmer()) {
                                properties.put(LUMINARY_HAS_WHITELEVEL, true);
                            }
                        }
                    }
                }
                logger.trace("addDiscoveredLuminary: ttUID: {}, bUID {}, label: {}", THING_TYPE_LUMINARY, bridgeUID,
                        unit.name);
                logger.trace("addDiscoveredLuminary: prop: {}", properties);
                DiscoveryResult discoveryResult = DiscoveryResultBuilder.create(thingUID)
                        .withThingType(THING_TYPE_LUMINARY).withProperties(properties).withBridge(bridgeUID)
                        .withRepresentationProperty(LUMINARY_UID).withLabel(unit.name).build();
                thingDiscovered(discoveryResult);
            } else {
                logger.warn("addDiscoveredLuminary: bridgeUID is null");
            }
        } catch (Exception e) {
            logger.warn("addDiscoveredLuminary: Exception {}", e);
        }
    }

    private void addDiscoveredScene(CasambiMessageScene scene) {
        try {
            if (bridgeUID != null) {
                ThingUID localBridgeUID = bridgeUID;
                String uniqueID = CasambiSceneHandler.getUidFromId(scene.id);
                ThingUID thingUID = new ThingUID(THING_TYPE_LUMINARY, localBridgeUID, uniqueID);
                logger.debug("addDiscoveredScene: tUID: {}, id {}, name {}, uid {}", thingUID, scene.id, scene.name,
                        uniqueID);
                Map<String, Object> properties = new HashMap<>();
                properties.put(SCENE_NAME, scene.name);
                properties.put(SCENE_ID, scene.id);
                properties.put(SCENE_UID, uniqueID);
                logger.trace("addDiscoveredScene: ttUID: {}, bUID {}, label: {}", THING_TYPE_SCENE, bridgeUID,
                        scene.name);
                logger.trace("addDiscoveredScene: prop: {}", properties);
                DiscoveryResult discoveryResult = DiscoveryResultBuilder.create(thingUID)
                        .withThingType(THING_TYPE_SCENE).withProperties(properties).withBridge(bridgeUID)
                        .withRepresentationProperty(SCENE_UID).withLabel(scene.name).build();
                thingDiscovered(discoveryResult);
            } else {
                logger.warn("addDiscoveredScene: bridgeUID is null");
            }
        } catch (Exception e) {
            logger.warn("addDiscoveredScene: Exception {}", e);
        }
    }

    private void addDiscoveredGroup(CasambiMessageGroup group) {
        try {
            if (bridgeUID != null) {
                ThingUID localBridgeUID = bridgeUID;
                String uniqueID = CasambiGroupHandler.getUidFromId(group.id);
                ThingUID thingUID = new ThingUID(THING_TYPE_LUMINARY, localBridgeUID, uniqueID);
                logger.debug("addDiscoveredGroup: tUID: {}, id {}, name {}, uid {}", thingUID, group.id, group.name,
                        uniqueID);
                Map<String, Object> properties = new HashMap<>();
                properties.put(GROUP_NAME, group.name);
                properties.put(GROUP_ID, group.id);
                properties.put(GROUP_UID, uniqueID);
                logger.trace("addDiscoveredGroup: ttUID: {}, bUID {}, label: {}", THING_TYPE_GROUP, bridgeUID,
                        group.name);
                logger.trace("addDiscoveredGroup: prop: {}", properties);
                DiscoveryResult discoveryResult = DiscoveryResultBuilder.create(thingUID)
                        .withThingType(THING_TYPE_GROUP).withProperties(properties).withBridge(bridgeUID)
                        .withRepresentationProperty(GROUP_UID).withLabel(group.name).build();
                thingDiscovered(discoveryResult);
            } else {
                logger.warn("addDiscoveredGroup: bridgeUID is null");
            }
        } catch (Exception e) {
            logger.warn("addDiscoveredGroup: Exception {}", e);
        }
    }

    @Override
    public void setThingHandler(ThingHandler handler) {
        logger.trace("setThingHandler: ");
        if (handler instanceof CasambiBridgeHandler) {
            bridgeHandler = (CasambiBridgeHandler) handler;
            bridgeUID = handler.getThing().getUID();
        }
    }

    @Override
    public @Nullable ThingHandler getThingHandler() {
        logger.trace("getThingHandler: ");
        return bridgeHandler;
    }

    @Override
    public void activate() {
        if (bridgeHandler != null) {
            bridgeHandler.registerDiscoveryListener(this);
            logger.trace("activate: ");
        } else {
            logger.warn("activate: error - bridgeHandler is null");
        }
        super.activate(null);
    }

    @Override
    public void deactivate() {
        if (bridgeHandler != null) {
            bridgeHandler.unregisterDiscoveryListener();
            logger.trace("deactivate: ");
        } else {
            logger.warn("deactivate: error - bridgeHandler is null");
        }
        super.deactivate();
    }
}

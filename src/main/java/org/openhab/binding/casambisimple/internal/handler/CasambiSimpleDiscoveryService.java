/**
 * Copyright (c) 2010-2022 Contributors to the openHAB project
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

import static org.openhab.binding.casambisimple.internal.CasambiSimpleBindingConstants.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.casambisimple.internal.driver.CasambiSimpleDriverRest;
import org.openhab.binding.casambisimple.internal.driver.messages.CasambiSimpleMessageControl;
import org.openhab.binding.casambisimple.internal.driver.messages.CasambiSimpleMessageGroup;
import org.openhab.binding.casambisimple.internal.driver.messages.CasambiSimpleMessageNetworkState;
import org.openhab.binding.casambisimple.internal.driver.messages.CasambiSimpleMessageScene;
import org.openhab.binding.casambisimple.internal.driver.messages.CasambiSimpleMessageUnit;
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
 * The {@link CasambiSimpleDiscover} discovers luminaires, switches, scenes and groups on
 * the Casambi network
 *
 * @author Hein Osenberg - Initial contribution
 */
@NonNullByDefault
public class CasambiSimpleDiscoveryService extends AbstractDiscoveryService implements ThingHandlerService {

    private static final Logger logger = LoggerFactory.getLogger(CasambiSimpleDiscoveryService.class);

    public static final Set<ThingTypeUID> SUPPORTED_THING_TYPES = Set.of(THING_TYPE_LUMINAIRE, THING_TYPE_SCENE,
            THING_TYPE_GROUP);
    private static final int SEARCH_TIME = 10;

    private @Nullable CasambiSimpleBridgeHandler bridgeHandler;
    private @Nullable ThingUID bridgeUID;

    // --- Constructor ---------------------------------------------------------------------------------------------

    public CasambiSimpleDiscoveryService() {
        super(SUPPORTED_THING_TYPES, SEARCH_TIME);
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
            final CasambiSimpleBridgeHandler bridgeHandlerLocal = bridgeHandler;
            if (bridgeHandlerLocal != null) {
                final CasambiSimpleDriverRest casambiRestLocal = bridgeHandlerLocal.casambiRest;
                if (casambiRestLocal != null) {
                    final CasambiSimpleMessageNetworkState networkState = casambiRestLocal.getNetworkState();
                    if (networkState != null) {

                        // Initialize list of existing things and new units
                        CasambiSimpleDiscoverySet knownAndNewThings = new CasambiSimpleDiscoverySet(
                                bridgeHandlerLocal.thingsById);
                        Set<String> knownThings = knownAndNewThings.getKnown();
                        logger.debug("doDiscoveryScan: knownThings before scan {}", knownThings);

                        if (networkState.units != null) {
                            // Loop through luminaires removing from existing things and adding to new units
                            for (Entry<Integer, CasambiSimpleMessageUnit> unit : networkState.units.entrySet()) {
                                Integer id = unit.getValue().id;
                                Integer fixtureId = unit.getValue().fixtureId;
                                String uid = CasambiSimpleLuminaireHandler.getUidFromFixtureId(fixtureId);
                                logger.trace("doDiscoveryScan: got unit id {}, uid {}, name {}", id, uid,
                                        unit.getValue().name);
                                knownAndNewThings.update(uid, id);
                            }
                        }
                        if (networkState.scenes != null) {
                            // Loop through scenes removing from existing things and adding to new units
                            for (Entry<Integer, CasambiSimpleMessageScene> scene : networkState.scenes.entrySet()) {
                                Integer id = scene.getValue().id;
                                String uid = CasambiSimpleSceneHandler.getUidFromId(id);
                                logger.trace("doDiscoveryScan: got scene id {}, uid {}, name {}", id, uid,
                                        scene.getValue().name);
                                knownAndNewThings.update(uid, id);
                            }
                        }

                        if (networkState.groups != null) {
                            // Loop through scenes removing from existing things and adding to new units
                            for (Entry<Integer, CasambiSimpleMessageGroup> group : networkState.groups.entrySet()) {
                                Integer id = group.getValue().id;
                                String uid = CasambiSimpleGroupHandler.getUidFromId(id);
                                logger.trace("doDiscoveryScan: got group id {}, uid {}, name {}", id, uid,
                                        group.getValue().name);
                                knownAndNewThings.update(uid, id);
                            }
                        }

                        // Add new units to things (using newThings)
                        Set<String> newThings = knownAndNewThings.getNew();
                        logger.debug("doDiscoveryScan: adding new things {}", newThings);

                        for (String uidId : newThings) {
                            Integer id = bridgeHandlerLocal.thingsById.getId(uidId);
                            ThingTypeUID type = bridgeHandlerLocal.thingsById.getType(uidId);
                            CasambiSimpleMessageNetworkState networkStateLocal = networkState;
                            if (networkStateLocal != null) {
                                if (THING_TYPE_LUMINAIRE.equals(type) && networkStateLocal.units != null) {
                                    CasambiSimpleMessageUnit unit = networkStateLocal.units.get(id);
                                    if (unit != null) {
                                        addDiscoveredLuminaire(unit);
                                    }
                                } else if (THING_TYPE_SCENE.equals(type) && networkStateLocal.scenes != null) {
                                    CasambiSimpleMessageScene scene = networkStateLocal.scenes.get(id);
                                    if (scene != null) {
                                        addDiscoveredScene(scene);
                                    }
                                } else if (THING_TYPE_GROUP.equals(type) && networkStateLocal.groups != null) {
                                    CasambiSimpleMessageGroup scene = networkStateLocal.groups.get(id);
                                    if (scene != null) {
                                        addDiscoveredGroup(scene);
                                    }
                                }
                            }
                        }

                        // Remove things without units (using knownThings)
                        knownThings = knownAndNewThings.getKnown();
                        logger.debug("doDiscoveryScan: removing undiscovered known things {}", knownThings);

                        for (String uidId : knownThings) {
                            logger.trace("doDiscoveryScan: removing undiscovered known thing uidId {}", uidId);
                            Thing thing = bridgeHandlerLocal.thingsById.getThing(uidId);
                            if (thing != null) {
                                ThingTypeUID type = bridgeHandlerLocal.thingsById.getType(uidId);
                                if (THING_TYPE_LUMINAIRE.equals(type)) {
                                    CasambiSimpleLuminaireHandler handler = (CasambiSimpleLuminaireHandler) thing
                                            .getHandler();
                                    if (handler != null) {
                                        handler.dispose();
                                    }
                                } else if (THING_TYPE_SCENE.equals(type)) {
                                    CasambiSimpleSceneHandler handler = (CasambiSimpleSceneHandler) thing.getHandler();
                                    if (handler != null) {
                                        handler.dispose();
                                    }
                                } else if (THING_TYPE_GROUP.equals(type)) {
                                    CasambiSimpleGroupHandler handler = (CasambiSimpleGroupHandler) thing.getHandler();
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

    private void addDiscoveredLuminaire(CasambiSimpleMessageUnit unit) {
        try {
            final String uniqueID = CasambiSimpleLuminaireHandler.getUidFromFixtureId(unit.fixtureId);
            final ThingUID localBridgeUID = bridgeUID;
            if (localBridgeUID != null) {
                ThingUID thingUID = new ThingUID(THING_TYPE_LUMINAIRE, localBridgeUID, uniqueID);
                logger.debug("addDiscoveredLuminaire: tUID: {}, id {}, name {}, uid {}", thingUID, unit.id, unit.name,
                        unit.fixtureId);
                final Map<String, Object> properties = new HashMap<>();
                properties.put(LUMINAIRE_NAME, unit.name);
                properties.put(LUMINAIRE_ID, unit.id);
                properties.put(LUMINAIRE_UID, uniqueID);
                if (unit.controls != null) {
                    for (CasambiSimpleMessageControl control : unit.controls[0]) {
                        if (control != null) {
                            if (control.isDimmer()) {
                                properties.put(LUMINAIRE_HAS_DIMMER, true);
                            }
                            if (control.isColor()) {
                                properties.put(LUMINAIRE_HAS_COLOR, true);
                            }
                            if (control.isCCT()) {
                                properties.put(LUMINAIRE_HAS_CCT, true);
                                properties.put(LUMINAIRE_TEMPERATURE_MIN, control.getMin());
                                properties.put(LUMINAIRE_TEMPERATURE_MAX, control.getMax());
                            }
                        }
                    }
                }
                logger.debug("addDiscoveredLuminaire: ttUID: {}, bUID {}, label: {}", THING_TYPE_LUMINAIRE, bridgeUID,
                        unit.name);
                logger.debug("addDiscoveredLuminaire: prop: {}", properties);
                DiscoveryResult discoveryResult = DiscoveryResultBuilder.create(thingUID)
                        .withThingType(THING_TYPE_LUMINAIRE).withProperties(properties).withBridge(bridgeUID)
                        .withRepresentationProperty(LUMINAIRE_UID).withLabel(unit.name).build();
                thingDiscovered(discoveryResult);
            } else {
                logger.warn("addDiscoveredLuminaire: bridgeUID is null");
            }
        } catch (Exception e) {
            logger.warn("addDiscoveredLuminaire: Exception {}", e.getMessage());
        }
    }

    private void addDiscoveredScene(CasambiSimpleMessageScene scene) {
        try {
            final String uniqueID = CasambiSimpleSceneHandler.getUidFromId(scene.id);
            final ThingUID localBridgeUID = bridgeUID;
            if (localBridgeUID != null) {
                ThingUID thingUID = new ThingUID(THING_TYPE_LUMINAIRE, localBridgeUID, uniqueID);
                logger.debug("addDiscoveredScene: tUID: {}, id {}, name {}, uid {}", thingUID, scene.id, scene.name,
                        uniqueID);
                final Map<String, Object> properties = new HashMap<>();
                properties.put(SCENE_NAME, scene.name);
                properties.put(SCENE_ID, scene.id);
                properties.put(SCENE_UID, uniqueID);
                logger.debug("addDiscoveredScene: ttUID: {}, bUID {}, label: {}", THING_TYPE_SCENE, bridgeUID,
                        scene.name);
                logger.debug("addDiscoveredScene: prop: {}", properties);
                DiscoveryResult discoveryResult = DiscoveryResultBuilder.create(thingUID)
                        .withThingType(THING_TYPE_SCENE).withProperties(properties).withBridge(bridgeUID)
                        .withRepresentationProperty(SCENE_UID).withLabel(scene.name).build();
                thingDiscovered(discoveryResult);
            } else {
                logger.warn("addDiscoveredScene: bridgeUID is null");
            }
        } catch (Exception e) {
            logger.warn("addDiscoveredScene: Exception {}", e.getMessage());
        }
    }

    private void addDiscoveredGroup(CasambiSimpleMessageGroup group) {
        try {
            final String uniqueID = CasambiSimpleGroupHandler.getUidFromId(group.id);
            final ThingUID localBridgeUID = bridgeUID;
            if (localBridgeUID != null) {
                final ThingUID thingUID = new ThingUID(THING_TYPE_LUMINAIRE, localBridgeUID, uniqueID);
                logger.debug("addDiscoveredGroup: tUID: {}, id {}, name {}, uid {}", thingUID, group.id, group.name,
                        uniqueID);
                final Map<String, Object> properties = new HashMap<>();
                properties.put(GROUP_NAME, group.name);
                properties.put(GROUP_ID, group.id);
                properties.put(GROUP_UID, uniqueID);
                logger.debug("addDiscoveredGroup: ttUID: {}, bUID {}, label: {}", THING_TYPE_GROUP, bridgeUID,
                        group.name);
                logger.debug("addDiscoveredGroup: prop: {}", properties);
                DiscoveryResult discoveryResult = DiscoveryResultBuilder.create(thingUID)
                        .withThingType(THING_TYPE_GROUP).withProperties(properties).withBridge(bridgeUID)
                        .withRepresentationProperty(GROUP_UID).withLabel(group.name).build();
                thingDiscovered(discoveryResult);
            } else {
                logger.warn("addDiscoveredGroup: bridgeUID is null");
            }
        } catch (Exception e) {
            logger.warn("addDiscoveredGroup: Exception {}", e.getMessage());
        }
    }

    @Override
    public void setThingHandler(ThingHandler handler) {
        if (handler instanceof CasambiSimpleBridgeHandler) {
            bridgeHandler = (CasambiSimpleBridgeHandler) handler;
            bridgeUID = handler.getThing().getUID();
        }
    }

    @Override
    public @Nullable ThingHandler getThingHandler() {
        return bridgeHandler;
    }

    @Override
    public void activate() {
        if (bridgeHandler != null) {
            bridgeHandler.registerDiscoveryListener(this);
        } else {
            logger.warn("activate: error - bridgeHandler is null");
        }
        super.activate(null);
    }

    @Override
    public void deactivate() {
        if (bridgeHandler != null) {
            bridgeHandler.unregisterDiscoveryListener();
        } else {
            logger.warn("deactivate: error - bridgeHandler is null");
        }
        super.deactivate();
    }
}

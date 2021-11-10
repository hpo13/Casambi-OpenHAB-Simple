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
package org.openhab.binding.casambitest.internal.handler;

import static org.openhab.binding.casambitest.internal.CasambiBindingConstants.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.casambitest.internal.driver.messages.CasambiMessageUnit;
import org.openhab.core.config.discovery.AbstractDiscoveryService;
import org.openhab.core.config.discovery.DiscoveryResult;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
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
        if (bridgeHandler != null) {
            bridgeHandler.scheduleDiscoveryScan();
        } else {
            logger.warn("startScan: bridge not set up. Not scanning.");
        }
    }

    @Override
    protected synchronized void stopScan() {
        logger.debug("stopScan: ");
        // FIXME: muss hier ein laufender Scan abgebrochen werden?
        super.stopScan();
    }

    protected void addDiscoveredLuminary(CasambiMessageUnit unit) {
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

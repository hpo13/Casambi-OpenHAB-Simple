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

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.casambitest.internal.CasambiBindingConstants;
import org.openhab.binding.casambitest.internal.driver.messages.CasambiMessageUnit;
import org.openhab.core.config.discovery.AbstractDiscoveryService;
import org.openhab.core.config.discovery.DiscoveryResult;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.config.discovery.DiscoveryService;
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
public class CasambiDiscoveryService extends AbstractDiscoveryService implements DiscoveryService, ThingHandlerService {

    private static final Logger logger = LoggerFactory.getLogger(CasambiDiscoveryService.class);

    public static final Set<ThingTypeUID> SUPPORTED_THING_TYPES = Set.of(THING_TYPE_LUMINARY, THING_TYPE_SCENE);
    private static final int SEARCH_TIME = 10;

    @Nullable
    private CasambiBridgeHandler bridgeHandler;
    @Nullable
    private ThingUID bridgeUID;

    public CasambiDiscoveryService() {
        super(SUPPORTED_THING_TYPES, SEARCH_TIME);
        logger.warn("CasambiDiscoveryService: 0 arg constructor.");
    }

    public CasambiDiscoveryService(int timeout) throws IllegalArgumentException {
        super(SUPPORTED_THING_TYPES, timeout);
        logger.warn("CasambiDiscoveryService: 1 arg constructor.");
    }

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

    protected void addDiscoveredLuminary(CasambiMessageUnit unit) {
        try {
            if (bridgeUID != null) {
                ThingUID localBridgeUID = bridgeUID;
                String uniqueID = "lum" + unit.fixtureId.toString();
                ThingUID thingUID = new ThingUID(CasambiBindingConstants.THING_TYPE_LUMINARY, localBridgeUID, uniqueID);
                logger.debug("addDiscoveredLuminary: uid: {}, id {}, name {}, uid {}", thingUID, unit.id, unit.name,
                        unit.fixtureId);
                Map<String, Object> properties = new HashMap<>(1);
                properties.put(CasambiBindingConstants.DEVICE_NAME, unit.name);
                properties.put(CasambiBindingConstants.DEVICE_ID, unit.id);
                properties.put(CasambiBindingConstants.DEVICE_UID, uniqueID);
                DiscoveryResult discoveryResult = DiscoveryResultBuilder.create(thingUID).withProperties(properties)
                        .withBridge(bridgeUID).withLabel(unit.name).build();
                // .withRepresentationProperty(CasambiBindingConstants.DEVICE_UID)
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
        logger.debug("setThingHandler:");
        if (handler instanceof CasambiBridgeHandler) {
            bridgeHandler = (CasambiBridgeHandler) handler;
            bridgeUID = handler.getThing().getUID();
        }
    }

    @Override
    public @Nullable ThingHandler getThingHandler() {
        logger.debug("getThingHandler:");
        return bridgeHandler;
    }

    @Override
    public void activate() {
        logger.debug("activate:");
        final CasambiBridgeHandler handler = bridgeHandler;
        if (handler != null) {
            handler.scheduleDiscoveryScan();
        }
    }

    @Override
    public void deactivate() {
        logger.debug("deactivate:");
        removeOlderResults(new Date().getTime(), bridgeUID);
        /*
         * final CasambiBridgeHandler handler = bridgeHandler;
         * if (handler != null) {
         * handler.unregisterDiscoveryListener();
         * }
         */
    }
}

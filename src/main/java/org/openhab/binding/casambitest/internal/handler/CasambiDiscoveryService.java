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
import org.openhab.binding.casambitest.internal.CasambiBindingConstants;
import org.openhab.binding.casambitest.internal.driver.messages.CasambiMessageUnit;
import org.openhab.core.config.discovery.AbstractDiscoveryService;
import org.openhab.core.config.discovery.DiscoveryResult;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.config.discovery.DiscoveryService;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link CasambiDiscover} discovers luminaries, switches, scenes and groups on
 * the casambi network
 *
 * @author Hein Osenberg - Initial contribution
 */
@NonNullByDefault
@Component(service = DiscoveryService.class, immediate = true, configurationPid = "discovery.casambitest")
public class CasambiDiscoveryService extends AbstractDiscoveryService {

    @Nullable
    private static CasambiBridgeHandler bridge;

    private static final Logger logger = LoggerFactory.getLogger(CasambiDiscoveryService.class);

    public CasambiDiscoveryService() {
        super(10 * 1000);
        logger.warn("CasambiDiscoveryService: 0 arg constructor.");
    }

    public CasambiDiscoveryService(int timeout) throws IllegalArgumentException {
        super(timeout);
        logger.warn("CasambiDiscoveryService: 1 arg constructor.");
    }

    @Override
    public Set<ThingTypeUID> getSupportedThingTypes() {
        return Set.of(THING_TYPE_LUMINARY, THING_TYPE_SCENE);
    }

    @Override
    protected void startScan() {
        logger.debug("startScan: ");
        if (bridge != null) {
            bridge.scheduleDiscoveryScan();
        } else {
            logger.warn("startScan: bridge not set up. Not scanning.");
        }
    }

    protected void addDiscoveredLuminary(CasambiMessageUnit unit) {
        try {
            ThingUID bridgeUID = bridge.getThing().getUID();
            if (bridgeUID != null) {
                logger.debug("addDiscoveredLuminary: bridge: {}, id {}, name, uid {}", bridgeUID, unit.id, unit.name,
                        unit.fixtureId);
                ThingUID thingUID = new ThingUID(CasambiBindingConstants.BINDING_ID, bridgeUID,
                        ":lum" + unit.fixtureId.toString());
                Map<String, Object> properties = new HashMap<>(1);
                properties.put(CasambiBindingConstants.DEVICE_NAME, unit.name);
                properties.put(CasambiBindingConstants.DEVICE_ID, unit.id);
                DiscoveryResult discoveryResult = DiscoveryResultBuilder.create(thingUID).withProperties(properties)
                        .withBridge(bridgeUID).withLabel(unit.name).build();
                thingDiscovered(discoveryResult);
            } else {
                logger.warn("addDiscoveredLuminary: bridgeUID is null");
            }
        } catch (Exception e) {
            logger.warn("addDiscoveredLuminary: Exception {}", e);
        }
    }

    protected void setBridge(CasambiBridgeHandler bridgeHandler) {
        bridge = bridgeHandler;
    }
}

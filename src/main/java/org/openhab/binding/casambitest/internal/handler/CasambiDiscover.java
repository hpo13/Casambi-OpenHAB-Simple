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

import java.util.Map.Entry;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.casambitest.internal.driver.messages.CasambiMessageControl;
import org.openhab.binding.casambitest.internal.driver.messages.CasambiMessageNetworkState;
import org.openhab.binding.casambitest.internal.driver.messages.CasambiMessageScene;
import org.openhab.binding.casambitest.internal.driver.messages.CasambiMessageUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link CasambiDiscover} discovers luminaries, switches, scenes and groups on
 * the casamib network
 *
 * @author Hein Osenberg - Initial contribution
 */
@NonNullByDefault
public class CasambiDiscover {

    @Nullable
    private CasambiBridgeHandler bridge;

    private static final Logger logger = LoggerFactory.getLogger(CasambiDiscover.class);

    public static void discoverDevices(@Nullable CasambiMessageNetworkState networkState) {
        logger.debug("discoverDevices: started.");
        if (networkState != null) {
            if (networkState.units != null) {
                logger.debug("discoverDevices: doing devices.");
                if (networkState.units != null) {
                    for (Entry<Integer, CasambiMessageUnit> unit : networkState.units.entrySet()) {
                        String type = unit.getValue().type;
                        Integer id = unit.getKey();
                        String name = unit.getValue().name;
                        Integer uid = unit.getValue().fixtureId;
                        CasambiMessageControl[][] controls = unit.getValue().controls;
                        logger.debug("discoverDevices: found device - type {}, id {}, name {}, uid {}", type, id, name,
                                uid);
                        if (controls != null) {
                            for (CasambiMessageControl[] controls1 : controls) {
                                for (CasambiMessageControl control : controls1) {
                                    String cType = control.type;
                                    String cName = control.name;
                                    Float cValue = control.value;
                                    logger.debug("discoverDevices:     control type {}, name {}, value {}", cType,
                                            cName, cValue);
                                }
                            }
                        }
                    }
                }
            }
            if (networkState.scenes != null) {
                logger.debug("discoverDevices: doing scenes.");
                if (networkState.scenes != null) {
                    for (Entry<Integer, CasambiMessageScene> scene : networkState.scenes.entrySet()) {
                        Integer id = scene.getKey();
                        String name = scene.getValue().name;
                        logger.debug("discoverDevices: found scene id {}, name {}, uid {}", id, name);
                        if (scene.getValue().units != null) {
                            for (Entry<Integer, CasambiMessageUnit> unit : scene.getValue().units.entrySet()) {
                                logger.debug("discoverDevices:     scene unit id {}", unit.getValue().id);
                            }
                        }
                    }
                }
            }
            if (networkState.groups != null) {
                logger.debug("discoverDevices: doing groups.");
            }
        }
    }

    public void setBridge(CasambiBridgeHandler bridgeHandler) {
        bridge = bridgeHandler;
    }
}
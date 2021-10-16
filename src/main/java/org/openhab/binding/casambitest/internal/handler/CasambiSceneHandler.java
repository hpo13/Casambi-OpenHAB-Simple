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

import static org.openhab.binding.casambitest.internal.CasambiTestBindingConstants.*;

import java.math.BigDecimal;
import java.util.Arrays;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.casambitest.internal.driver.messages.CasambiMessageNetworkState;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.binding.BridgeHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link casambiTestHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Hein Osenberg - Initial contribution
 */
@NonNullByDefault
public class CasambiSceneHandler extends BaseThingHandler {

    private final Logger logger = LoggerFactory.getLogger(CasambiSceneHandler.class);

    private @Nullable Integer sceneId;

    // private @Nullable CasambiTestConfiguration config;

    public CasambiSceneHandler(Thing thing) {
        super(thing);
        // logger.debug("constructor: scene");
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        logger.debug("handleCommand: channel uid {}, command {}", channelUID, command);
        CasambiBridgeHandler bridgeHandler = getBridgeHandler();
        Boolean doRefresh = false;
        if (bridgeHandler != null) {
            // logger.debug("handleCommand: bridge handler ok.");
            if (CHANNEL_SCENE.equals(channelUID.getId())) {
                if (command instanceof RefreshType) {
                    doRefresh = true;
                } else if (command instanceof OnOffType) {
                    try {
                        if ((OnOffType) command == OnOffType.ON) {
                            bridgeHandler.casambi.turnSceneOn(sceneId);
                        } else {
                            bridgeHandler.casambi.turnSceneOff(sceneId);
                        }
                    } catch (Exception e) {
                        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                                String.format("Channel %s Exception %s", channelUID.toString(), e.toString()));
                    }
                } else {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                            String.format("Channel %s Illegal command %s", channelUID.toString(), command.toString()));
                }
            } else {
                logger.warn("handleCommand: unexpected channel id {}", channelUID.getId());
            }
            if (doRefresh) {
                // Send refresh command here
                try {
                    logger.debug("handleCommand: uid {} get unit state", channelUID);
                    CasambiMessageNetworkState networkState = bridgeHandler.casambi.getNetworkState();
                    updateSceneState(networkState);
                    // TODO: parse unitState and updateStatus accordingly
                } catch (Exception e) {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, String
                            .format("Channel %s Exception getting unit state %s", channelUID.toString(), e.toString()));
                }
            }
        } else {
            // logger.warn("handleCommand: bridge handler is null.");
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    String.format("Channel %s Bridge or Handler not found ", channelUID.toString()));
        }
    }

    @Override
    public void initialize() {
        logger.debug("initialize: setting up scene");
        Bridge bridge = getBridge();

        if (bridge == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "No bridge configured");
        } else {
            updateStatus(ThingStatus.ONLINE);
        }
        sceneId = ((BigDecimal) this.thing.getConfiguration().get(SCENE_ID)).intValueExact();
        CasambiBridgeHandler h = (CasambiBridgeHandler) bridge.getHandler();
        if (sceneId != null && h != null) {
            logger.debug("initialize: adding thing to mapping");
            h.putSceneById(sceneId, this.thing);
        }
        logger.debug("initialize: uid {}, id {}", this.thing.getUID(), sceneId);
    }

    @Override
    public void dispose() {
        logger.debug("dispose: dispose scene");
    };

    @Override
    public void handleRemoval() {
        logger.debug("handleRemoval: removing scene");
        updateStatus(ThingStatus.REMOVED);
    }

    @Override
    public void bridgeStatusChanged(ThingStatusInfo info) {
        logger.debug("bridgeStatusChanged: {}", info);
        Bridge bridge = getBridge();

        if (bridge == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "No bridge configured");
        } else {
            ThingStatus bridgeStatus = bridge.getStatus();
            if (bridgeStatus.equals(ThingStatus.ONLINE)) {
                updateStatus(ThingStatus.ONLINE, ThingStatusDetail.NONE);
            } else if (bridgeStatus.equals(ThingStatus.OFFLINE)) {
                updateStatus(ThingStatus.ONLINE, ThingStatusDetail.BRIDGE_OFFLINE);
            } else {
                logger.debug("bridgeStatusChanged: unexpected bridge status {}", bridgeStatus);
            }
        }
    }

    @Nullable
    protected CasambiBridgeHandler getBridgeHandler() {
        BridgeHandler handler;
        Bridge bridge = this.getBridge();
        if (bridge != null) {
            handler = bridge.getHandler();
        } else {
            handler = null;
        }
        return (CasambiBridgeHandler) handler;
    }

    @Override
    public void updateState(ChannelUID chan, State state) {
        logger.debug("updateState: channel {}, state {}", chan, state);
        super.updateState(chan, state);
    }

    private void updateSceneState(@Nullable CasambiMessageNetworkState s) {
        logger.debug("updateSceneState: id {}, name {}", s.id, s.name);
        if (s != null && s.activeScenes != null) {
            if (Arrays.asList(s.activeScenes).contains(sceneId)) {
                updateState(CHANNEL_SCENE, OnOffType.ON);
            } else {
                updateState(CHANNEL_SCENE, OnOffType.OFF);
            }
        } else {
            logger.debug("upateUnitState: scene info null");
        }
    }

}

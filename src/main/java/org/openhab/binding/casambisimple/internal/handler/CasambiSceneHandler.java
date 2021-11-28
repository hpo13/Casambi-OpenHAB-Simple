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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.casambisimple.internal.driver.CasambiDriverSocket;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PercentType;
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
 * The {@link CasambiSceneHandler} allows to control groups of scenes
 *
 * Scenes as defined by the Casambi system can be controlled through OpenHAB
 * things. Only one dimmer channel is supported which controls the dim level
 * of the scene. The channel is write only (that is, the dim level cannot be read back).
 *
 * @author Hein Osenberg - Initial contribution
 */
@NonNullByDefault
public class CasambiSceneHandler extends BaseThingHandler {

    private final Logger logger = LoggerFactory.getLogger(CasambiSceneHandler.class);

    private Integer sceneId = 0;
    private String sceneUid = "";

    // private @Nullable CasambiTestConfiguration config;

    public CasambiSceneHandler(Thing thing) {
        super(thing);
        sceneId = Integer.valueOf(thing.getConfiguration().get(SCENE_ID).toString());
        sceneUid = getUidFromId(sceneId);
        // logger.debug("constructor: scene");
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        logger.debug("handleCommand: channel uid {}, command {}", channelUID, command);
        CasambiBridgeHandler bridgeHandler = getBridgeHandler();
        boolean doRefresh = false;
        if (bridgeHandler != null) {
            CasambiDriverSocket casambiSocketLocal = bridgeHandler.casambiSocket;
            if (casambiSocketLocal != null) {
                if (SCENE_CHANNEL_ONOFF.equals(channelUID.getId())) {
                    try {
                        if (command instanceof RefreshType) {
                            doRefresh = true;
                        } else if (command instanceof OnOffType) {
                            casambiSocketLocal.setSceneOnOff(sceneId, command == OnOffType.ON);
                            // } else if (command instanceof PercentType) {
                            // bridgeHandler.casambiSocket.setSceneLevel(sceneId, ((PercentType) command).floatValue() /
                            // 100);
                        } else {
                            logger.warn("handleCommand: unexpected command type {}", command.getClass());
                        }
                    } catch (Exception e) {
                        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                                String.format("Channel %s Exception %s", channelUID.toString(), e.toString()));
                    }
                } else if (SCENE_CHANNEL_DIM.equals(channelUID.getId())) {
                    try {
                        if (command instanceof RefreshType) {
                            doRefresh = true;
                        } else if (command instanceof PercentType) {
                            casambiSocketLocal.setSceneLevel(sceneId, ((PercentType) command).floatValue() / 100);
                        } else {
                            logger.warn("handleCommand: unexpected command type {}", command.getClass());
                        }
                    } catch (Exception e) {
                        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                                String.format("Channel %s Exception %s", channelUID.toString(), e.toString()));
                    }
                } else {
                    logger.warn("handleCommand: unexpected channel id {}", channelUID.getId());
                }
                if (doRefresh) {
                    logger.trace("handleCommand: (Scene) doRefresh NOP");
                    // Send refresh command here
                }
            }
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    String.format("Channel %s Bridge or Handler not found ", channelUID.toString()));
        }
    }

    @Override
    public void initialize() {
        // logger.debug("initialize: setting up scene");
        Bridge bridge = getBridge();

        if (bridge == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "No bridge configured");
        } else {
            updateStatus(ThingStatus.ONLINE);
        }
        // sceneId = ((BigDecimal) this.thing.getConfiguration().get(SCENE_ID)).intValueExact();
        // putSceneById(sceneId);
        logger.debug("initialize: uid {}, id {}", this.thing.getUID(), sceneId);
    }

    @Override
    public void dispose() {
        logger.debug("dispose: dispose scene");
    }

    @Override
    public void handleRemoval() {
        logger.debug("handleRemoval: removing scene");
        logger.debug("  removing from thingsById");
        CasambiThingsById thingsById = getBridgeHandler().thingsById;
        thingsById.remove(thingsById.uidIdCombine(sceneUid, sceneId));
        updateStatus(ThingStatus.REMOVED);
    }

    @Override
    public void bridgeStatusChanged(ThingStatusInfo info) {
        logger.debug("bridgeStatusChanged: {}, updating scene {}", info, sceneId);
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
        logger.trace("updateState: channel {}, state {}", chan, state);
        super.updateState(chan, state);
    }

    // Map Luminary ids to things. Needed to update thing status based on casambi message content

    // // Get thing corresponding to id
    // public static @Nullable Thing getSceneById(@Nullable Integer id) {
    // if (id != null) {
    // return scenesById.get(id);
    // } else {
    // return null;
    // }
    // }
    //
    // // Add a (new) thing to the mapping
    // private void putSceneById(@Nullable Integer id) {
    // logger.debug("putThingById: id {}", id);
    // if (id != null) {
    // scenesById.putIfAbsent(id, this.thing);
    // }
    // }
    //
    public static String getUidFromId(Integer id) {
        return "scn" + id.toString();
    }
}

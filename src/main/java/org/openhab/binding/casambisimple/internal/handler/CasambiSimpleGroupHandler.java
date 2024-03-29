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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.casambisimple.internal.driver.CasambiSimpleDriverSocket;
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
 * The {@link CasambiSimpleGroupHandler} allows to control groups of devices
 *
 * Groups of devices as defined by the Casambi system can be controlled through
 * OpenHAB things. Only one dimmer channel is supported which controls the dim level
 * of the group. The channel is write only (that is, the dim level cannot be read back).
 *
 * @author Hein Osenberg - Initial contribution
 */
@NonNullByDefault
public class CasambiSimpleGroupHandler extends BaseThingHandler {

    private final Logger logger = LoggerFactory.getLogger(CasambiSimpleGroupHandler.class);

    private Integer groupId = 0;
    private String groupUid = "";

    // private @Nullable CasambiTestConfiguration config;

    public CasambiSimpleGroupHandler(Thing thing) {
        super(thing);
        groupId = Float.valueOf(thing.getConfiguration().get(GROUP_ID).toString()).intValue();
        groupUid = getUidFromId(groupId);
        // logger.debug("constructor: group");
    }

    /**
     * hanldeCommand handles channel commands for group things
     *
     * Commands supported are refresh, onoff and dim
     *
     * @param channelUID
     * @param command
     */
    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        logger.debug("handleCommand: channel uid {}, command {}", channelUID, command);
        final CasambiSimpleBridgeHandler bridgeHandler = getBridgeHandler();
        boolean doRefresh = false;
        if (bridgeHandler != null) {
            final CasambiSimpleDriverSocket casambiSocketLocal = bridgeHandler.casambiSocket;
            if (casambiSocketLocal != null) {
                if (GROUP_CHANNEL_ONOFF.equals(channelUID.getId())) {
                    try {
                        if (command instanceof RefreshType) {
                            doRefresh = true;
                        } else if (command instanceof OnOffType) {
                            casambiSocketLocal.setGroupOnOff(groupId, command == OnOffType.ON);
                            // } else if (command instanceof PercentType) {
                            // bridgeHandler.casambiSocket.setGroupLevel(groupId, ((PercentType) command).floatValue() /
                            // 100);
                        } else {
                            logger.warn("handleCommand: unexpected command type {}", command.getClass());
                        }
                    } catch (Exception e) {
                        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                                String.format("Channel %s Exception %s", channelUID.toString(), e.toString()));
                    }
                } else if (GROUP_CHANNEL_DIM.equals(channelUID.getId())) {
                    try {
                        if (command instanceof RefreshType) {
                            doRefresh = true;
                        } else if (command instanceof PercentType) {
                            casambiSocketLocal.setGroupLevel(groupId, ((PercentType) command).floatValue() / 100);
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
                    logger.trace("handleCommand: (Group) doRefresh NOP");
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
        // logger.debug("initialize: setting up group");
        final Bridge bridge = getBridge();

        if (bridge == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "No bridge configured");
        } else {
            updateStatus(ThingStatus.ONLINE);
        }
        // groupId = ((BigDecimal) this.thing.getConfiguration().get(GROUP_ID)).intValueExact();
        // putGroupById(groupId);
        logger.debug("initialize: uid {}, id {}", this.thing.getUID(), groupId);
    }

    @Override
    public void dispose() {
        logger.debug("dispose: dispose group");
    }

    @Override
    public void handleRemoval() {
        logger.debug("handleRemoval: removing group");
        logger.debug("  removing from thingsById");
        final CasambiSimpleBridgeHandler localBridgeHandler = getBridgeHandler();
        if (localBridgeHandler != null) {
            final CasambiSimpleThingsById thingsById = localBridgeHandler.thingsById;
            thingsById.remove(thingsById.uidIdCombine(groupUid, groupId));
            updateStatus(ThingStatus.REMOVED);
        }
    }

    @Override
    public void bridgeStatusChanged(ThingStatusInfo info) {
        logger.debug("bridgeStatusChanged: {}, updating group {}", info, groupId);
        final Bridge bridge = getBridge();

        if (bridge == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "No bridge configured");
        } else {
            final ThingStatus bridgeStatus = bridge.getStatus();
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
    protected CasambiSimpleBridgeHandler getBridgeHandler() {
        BridgeHandler handler;
        final Bridge bridge = this.getBridge();
        if (bridge != null) {
            handler = bridge.getHandler();
        } else {
            handler = null;
        }
        return (CasambiSimpleBridgeHandler) handler;
    }

    @Override
    public void updateState(ChannelUID chan, State state) {
        logger.trace("updateState: channel {}, state {}", chan, state);
        super.updateState(chan, state);
    }

    public static String getUidFromId(Integer id) {
        return "grp" + id.toString();
    }
}

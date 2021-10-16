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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.casambitest.internal.driver.messages.CasambiMessageUnitState;
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
 * The {@link CasambiLuminaryHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Hein Osenberg - Initial contribution
 * @version V0.3 211010@hpo First version to actually control lights
 */
@NonNullByDefault
public class CasambiLuminaryHandler extends BaseThingHandler {

    private final Logger logger = LoggerFactory.getLogger(CasambiLuminaryHandler.class);

    private @Nullable Integer deviceId;

    // private @Nullable CasambiTestConfiguration config;

    public CasambiLuminaryHandler(Thing thing) {
        super(thing);
        // logger.debug("constructor: luminary");
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        logger.debug("handleCommand: channel uid {}, command {}", channelUID, command);
        CasambiBridgeHandler bridgeHandler = getBridgeHandler();
        Boolean doRefresh = false;
        if (bridgeHandler != null) {
            // logger.debug("handleCommand: bridge handler ok.");
            if (CHANNEL_SWITCH.equals(channelUID.getId())) {
                if (command instanceof RefreshType) {
                    doRefresh = true;
                } else if (command instanceof OnOffType) {
                    try {
                        if ((OnOffType) command == OnOffType.ON) {
                            bridgeHandler.casambi.turnUnitOn(deviceId);
                        } else {
                            bridgeHandler.casambi.turnUnitOff(deviceId);
                        }
                    } catch (Exception e) {
                        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                                String.format("Channel %s Exception %s", channelUID.toString(), e.toString()));
                    }
                } else {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                            String.format("Channel %s Illegal command %s", channelUID.toString(), command.toString()));
                }
            } else if (CHANNEL_DIM.equals(channelUID.getId())) {
                if (command instanceof RefreshType) {
                    doRefresh = true;
                } else if (command instanceof PercentType) {
                    try {
                        // logger.debug("handleCommand: uid {} dim unit", channelUID);
                        bridgeHandler.casambi.setUnitValue(deviceId, ((PercentType) command).floatValue() / 100);
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
                    CasambiMessageUnitState unitState = bridgeHandler.casambi.getUnitState(deviceId);
                    updateUnitState(unitState);
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
        // logger.debug("initialize: setting up luninary");
        Bridge bridge = getBridge();

        if (bridge == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "No bridge configured");
        } else {
            updateStatus(ThingStatus.ONLINE);
        }

        deviceId = ((BigDecimal) this.thing.getConfiguration().get(DEVICE_ID)).intValueExact();
        CasambiBridgeHandler h = (CasambiBridgeHandler) bridge.getHandler();
        if (deviceId != null && h != null) {
            logger.debug("initialize: adding thing to mapping");
            h.putThingById(deviceId, this.thing);
        }
        logger.debug("initialize: uid {}, id {}", this.thing.getUID(), deviceId);
    }

    @Override
    public void dispose() {
        logger.debug("dispose: dispose luninary");
    };

    @Override
    public void handleRemoval() {
        logger.debug("handleRemoval: removing luninary");
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

    private void updateUnitState(@Nullable CasambiMessageUnitState s) {
        logger.debug("updateUnitState: id {}, name {}", s.id, s.name);
        if (s != null) {
            // ThingStatus
            if (s.online) {
                updateStatus(ThingStatus.ONLINE);
            } else {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Device went offline");
            }
            // ChannelState
            updateState(CHANNEL_SWITCH, s.on ? OnOffType.ON : OnOffType.OFF);
            if (s.dimLevel != null) {
                updateState(CHANNEL_DIM, new PercentType(Math.round(s.dimLevel * 100)));
            }
        } else {
            logger.debug("upateUnitState: unit state is null");
        }
    }

}

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

import java.math.BigDecimal;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.casambitest.internal.driver.messages.CasambiMessageUnit;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.HSBType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.binding.BridgeHandler;
import org.openhab.core.thing.binding.builder.ThingBuilder;
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
 * @version V0.5 211105@hpo Discovery working (with removal), added class to handle uid-id combinations
 */
@NonNullByDefault
public class CasambiLuminaryHandler extends BaseThingHandler {

    public CasambiLuminaryConfiguration config;

    private final Logger logger = LoggerFactory.getLogger(CasambiLuminaryHandler.class);

    private Integer deviceId = 0;
    private String deviceUid = "";

    // --- Constructor ---------------------------------------------------------------------------------------------

    public CasambiLuminaryHandler(Thing thing) {
        super(thing);
        config = getConfigAs(CasambiLuminaryConfiguration.class);
        deviceId = Integer.valueOf(thing.getConfiguration().get(LUMINARY_ID).toString());
        deviceUid = thing.getConfiguration().get(LUMINARY_UID).toString();
        logger.info("constructor: luminary uid {}, id {}", deviceUid, deviceId);
    }

    // --- Overridden superclass methods ---------------------------------------------------------------------------

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        boolean commandHandled = false;
        logger.debug("handleCommand: channel uid {}, command {}", channelUID, command);
        CasambiBridgeHandler bridgeHandler = getBridgeHandler();
        if (bridgeHandler != null && bridgeHandler.casambiSocket != null) {
            try {
                if (!(command instanceof RefreshType)) {
                    if (LUMINARY_CHANNEL_ONOFF.equals(channelUID.getId())) {
                        // Set dim level (0-100)
                        if (command instanceof OnOffType) {
                            bridgeHandler.casambiSocket.setUnitOnOff(deviceId, command.equals(OnOffType.ON));
                            commandHandled = true;
                        }
                    } else if (LUMINARY_CHANNEL_DIMMER.equals(channelUID.getId())) {
                        // Set dim level (0-100)
                        if (command instanceof PercentType) {
                            bridgeHandler.casambiSocket.setUnitDimmer(deviceId,
                                    ((PercentType) command).floatValue() / 100);
                            commandHandled = true;
                        }
                    } else if (LUMINARY_CHANNEL_COLOR.equals(channelUID.getId())) {
                        // Set hue (0-360), saturation (0-100) and brightness (0-100)
                        logger.info("handleCommand: got color channel command {}", command);
                        if (command instanceof HSBType) {
                            String[] hsb = ((HSBType) command).toString().split(",");
                            if (hsb.length == 3) {
                                Float h = Float.valueOf(hsb[0]) / 360;
                                Float s = Float.valueOf(hsb[1]) / 100;
                                Float b = Float.valueOf(hsb[2]) / 100;
                                bridgeHandler.casambiSocket.setUnitHSB(deviceId, h, s, b);
                            } else {
                                logger.warn("handleCommand: illegal hsb value {}", command.toString());
                            }
                            commandHandled = true;
                        } else if (command instanceof OnOffType) {
                            bridgeHandler.casambiSocket.setUnitOnOff(deviceId, command.equals(OnOffType.ON));
                            commandHandled = true;
                        }
                    } else if (LUMINARY_CHANNEL_CCT.equals(channelUID.getId())) {
                        // Set color temperature (e.g. 2000 - 6500)
                        logger.info("handleCommand: got cct channel command {}", command);
                        if (command instanceof DecimalType) {
                            Float slider = ((PercentType) command).floatValue() / 100;
                            Float tMin = config.tempMin;
                            Float tMax = config.tempMax;
                            Float temp = tMin + (tMax - tMin) * slider;
                            bridgeHandler.casambiSocket.setUnitCCT(deviceId, temp);
                            commandHandled = true;
                        }
                    } else if (LUMINARY_CHANNEL_WHITELEVEL.equals(channelUID.getId())) {
                        // Set color balance color (0-100) and white (0-100)
                        logger.info("handleCommand: got colorbalance channel command {}", command);
                        if (command instanceof PercentType) {
                            Float slider = ((PercentType) command).floatValue();
                            bridgeHandler.casambiSocket.setUnitWhitelevel(deviceId, slider);
                            commandHandled = true;
                        }
                    } else {
                        logger.warn("handleCommand: unexpected channel id {}", channelUID.getId());
                    }
                    if (!commandHandled) {
                        logger.warn("handleCommand: channel {}, unexpected command type {}", channelUID,
                                command.getClass());
                    }
                }
            } catch (Exception e) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        String.format("Channel %s, Command %s, Exception %s", channelUID.toString(), command.getClass(),
                                e.toString()));
            }
            if (command instanceof RefreshType) {
                // Send refresh command here
                try {
                    logger.trace("handleCommand: uid {} get unit state", channelUID);
                    CasambiMessageUnit unitState = bridgeHandler.casambiRest.getUnitState(deviceId);
                    if (unitState != null) {
                        updateLuminaryState(unitState);
                    } else {
                        logger.debug("handleCommand: uid {}, unit state is null", channelUID);
                    }
                } catch (Exception e) {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, String
                            .format("Channel %s Exception getting unit state %s", channelUID.toString(), e.toString()));
                }
            }
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    String.format("Channel %s Bridge or Handler not found ", channelUID.toString()));
        }
    }

    @Override
    public void initialize() {
        deviceId = ((BigDecimal) this.thing.getConfiguration().get(LUMINARY_ID)).intValueExact();
        deviceUid = this.thing.getConfiguration().get(LUMINARY_UID).toString();
        ThingUID thingUid = this.thing.getUID();

        CasambiBridgeHandler bridgeHandler = getBridgeHandler();
        if (bridgeHandler != null) {

            bridgeHandler.thingsById.put(bridgeHandler.thingsById.uidIdCombine(deviceUid, deviceId), this.thing);
            logger.info("initialize: uid {}, id {}, thingUid {}", deviceUid, deviceId, thingUid);

            // FIXME: This shouldn't be done on every initialization but once after as scan

            // Get properties of the device
            boolean hasBri = ((Boolean) true).equals(this.thing.getConfiguration().get(LUMINARY_HAS_DIMMER));
            boolean hasCo = ((Boolean) true).equals(this.thing.getConfiguration().get(LUMINARY_HAS_COLOR));
            boolean hasCoTe = ((Boolean) true).equals(this.thing.getConfiguration().get(LUMINARY_HAS_CCT));
            boolean hasWhLv = ((Boolean) true).equals(this.thing.getConfiguration().get(LUMINARY_HAS_WHITELEVEL));

            logger.info("Thing {}: hasBri {}, hasCo {}, hasCoTe {}, hasCoBa {}, hasWhLv {}", deviceUid, hasBri, hasCo,
                    hasCoTe, hasWhLv);

            // Remove channels that have no corresponding control in the device
            ThingBuilder tb = editThing();
            for (Channel ch : this.thing.getChannels()) {
                logger.info("Thing {} has channel: uid {}, label {} type {}", deviceUid, ch.getUID(), ch.getLabel(),
                        ch.getChannelTypeUID());
                if (LUMINARY_CHANNEL_DIMMER.equals(ch.getUID().getId())) {
                    if (!hasBri) {
                        logger.info("Thing: {} removing DIMMER channel {}", deviceUid, ch.getUID());
                        tb.withoutChannel(ch.getUID());
                    }
                }
                if (LUMINARY_CHANNEL_COLOR.equals(ch.getUID().getId())) {
                    if (!hasCo) {
                        logger.info("Thing: {} removing COLOR channel {}", deviceUid, ch.getUID());
                        tb.withoutChannel(ch.getUID());
                    }
                }
                if (LUMINARY_CHANNEL_CCT.equals(ch.getUID().getId())) {
                    if (!hasCoTe) {
                        logger.info("Thing: {} Removing CCT channel {}", deviceUid, ch.getUID());
                        tb.withoutChannel(ch.getUID());
                    }
                }
                if (LUMINARY_CHANNEL_WHITELEVEL.equals(ch.getUID().getId())) {
                    if (!hasWhLv) {
                        logger.info("Thing: {} Removing WHITELEVEL channel {}", deviceUid, ch.getUID());
                        tb.withoutChannel(ch.getUID());
                    }
                }
            }
            updateThing(tb.build());

            updateStatus(ThingStatus.ONLINE);
        } else {
            logger.error("initialize: bridge handler is null");
        }
    }

    @Override
    public void dispose() {
        logger.debug("dispose: dispose luninary handler id {}, uid {}.", this.deviceId, this.deviceUid);
        super.dispose();
    };

    @Override
    public void handleRemoval() {
        logger.debug("handleRemoval: removing luninaryid {}, uid {}", this.deviceId, this.deviceUid);
        updateStatus(ThingStatus.REMOVED);
    }

    @Override
    public void bridgeStatusChanged(ThingStatusInfo info) {
        logger.debug("bridgeStatusChanged: {}, updating luminary {}", info, deviceId);
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
                logger.info("bridgeStatusChanged: unexpected bridge status {}", bridgeStatus);
            }
        }
    }

    // --- Instance methods ----------------------------------------------------------------------------------

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

    public void updateLuminaryState(CasambiMessageUnit state) {
        Float lvl = (state.dimLevel != null) ? state.dimLevel : 0;
        logger.trace("updateLuminaryState: id {} dimLevel {} online {}", deviceId, lvl, state.online);
        if (state.online != null && state.online) {
            updateStatus(ThingStatus.ONLINE);
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    String.format("Unit %d status offline", deviceId));
        }
        if (state.dimLevel == 0) {
            // updateState(LUMINARY_CHANNEL_SWITCH, OnOffType.OFF);
            updateState(LUMINARY_CHANNEL_DIMMER, new PercentType(0));
        } else {
            // updateState(LUMINARY_CHANNEL_SWITCH, OnOffType.ON);
            updateState(LUMINARY_CHANNEL_DIMMER, new PercentType(Math.round(lvl * 100)));
        }
    }

    public void updateLuminaryStatus(ThingStatus t) {
        updateStatus(t);
    }

    // Map Luminary uids to things. Needed to update thing status based on casambi message content and for discovery

    public String getUid() {
        return this.thing.getConfiguration().getProperties().get(LUMINARY_UID).toString();
    }

    // --- Static methods ----------------------------------------------------------------------------------

    public static String getUidFromFixtureId(Integer fixtureId) {
        return "lum" + fixtureId.toString();
    }

}
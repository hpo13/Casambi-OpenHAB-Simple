/**
d * Copyright (c) 2010-2022 Contributors to the openHAB project
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

import java.math.BigDecimal;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.casambisimple.internal.driver.CasambiSimpleDriverRest;
import org.openhab.binding.casambisimple.internal.driver.CasambiSimpleDriverSocket;
import org.openhab.binding.casambisimple.internal.driver.messages.CasambiSimpleMessageUnit;
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
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.binding.BridgeHandler;
import org.openhab.core.thing.binding.builder.ThingBuilder;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link CasambiSimpleLuminaire Handler} allows to control luminaire devices
 *
 * Luminaires as defined by the Casambi system can be controlled through OpenHAB
 * things. Several channels are supported for brightness, color, color temperature
 * and white level.
 *
 * @author Hein Osenberg - Initial contribution
 * @version V0.3 211010@hpo First version to actually control lights
 * @version V0.5 211105@hpo Discovery working (with removal), added class to handle uid-id combinations
 */
@NonNullByDefault
public class CasambiSimpleLuminaireHandler extends BaseThingHandler {

    public CasambiSimpleLuminaireConfiguration config;

    private final Logger logger = LoggerFactory.getLogger(CasambiSimpleLuminaireHandler.class);

    private Integer deviceId = 0;
    private String deviceUid = "";

    // --- Constructor ---------------------------------------------------------------------------------------------

    /**
     * Initializes a luminaire instance
     * Calls the super method and sets up configuration, deviceId and deviceUid. The real work is done by initialize().
     *
     * @param thing thing for the luminaire
     */
    public CasambiSimpleLuminaireHandler(Thing thing) {
        super(thing);
        config = getConfigAs(CasambiSimpleLuminaireConfiguration.class);
        deviceId = Float.valueOf(thing.getConfiguration().get(LUMINAIRE_ID).toString()).intValue();
        deviceUid = thing.getConfiguration().get(LUMINAIRE_UID).toString();
        logger.info("constructor: luminaire uid {}, id {}", deviceUid, deviceId);
    }

    // --- Overridden superclass methods ---------------------------------------------------------------------------

    /**
     * handleCommand handles commands for the luminaire
     *
     * Currently the following channels are supported
     * <ul>
     * <li>LUMINAIRE_CHANNEL_ONOFF FIXME: implement correctly within the handler (save/restore dim level)
     * <li>LUMINAIRE_CHANNEL_DIMMER to set the devices dim level (0 - 100)
     * <li>LUMINAIRE_CHANNEL_COLOR to set the devices color (hue, sat, bri, 0-360, 0-100)
     * <li>LUMINAIRE_CHANNEL_CCT to set the devices color temperature (between min and max parameters)
     * <li>LUMINAIRE_CHANNEL_WHITELEVEL
     * </ul>
     * Command types supported are
     * <ul>
     * <li>RefreshType updates the luminaires state
     * <li>PercentType used for dimmer and white level
     * <li>OnOffType does not do work FIXME: see above
     * <li>HSBType used for color
     * <li>DecimalType used for color temperature
     * </ul>
     * The input ranges are converted to the ranges needed by the casambi driver
     *
     * @param channelUID channel to act on
     * @param command command
     */
    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        boolean commandHandled = false;
        logger.debug("handleCommand: channel uid {}, command {}", channelUID, command);
        final CasambiSimpleBridgeHandler bridgeHandler = getBridgeHandler();
        if (bridgeHandler != null) {
            final CasambiSimpleDriverRest casambiRestCopy = bridgeHandler.casambiRest;
            final CasambiSimpleDriverSocket casambiSocketCopy = bridgeHandler.casambiSocket;
            if (casambiSocketCopy != null && casambiRestCopy != null) {
                try {
                    if (!(command instanceof RefreshType)) {
                        if (LUMINAIRE_CHANNEL_ONOFF.equals(channelUID.getId())) {
                            logger.trace("handleCommand: got ONOFF channel command {}", command);
                            // Set dim level (0-100)
                            if (command instanceof OnOffType) {
                                casambiSocketCopy.setUnitOnOff(deviceId, command.equals(OnOffType.ON));
                                commandHandled = true;
                            }
                        } else if (LUMINAIRE_CHANNEL_DIMMER.equals(channelUID.getId())) {
                            logger.trace("handleCommand: got DIMMER channel command {}", command);
                            // Set dim level (0-100)
                            if (command instanceof PercentType) {
                                casambiSocketCopy.setUnitDimmer(deviceId, ((PercentType) command).floatValue() / 100);
                                commandHandled = true;
                            }
                        } else if (LUMINAIRE_CHANNEL_COLOR.equals(channelUID.getId())) {
                            // Set hue (0-360), saturation (0-100) and brightness (0-100)
                            logger.trace("handleCommand: got COLOR channel command {}", command);
                            if (command instanceof HSBType) {
                                String[] hsb = ((HSBType) command).toString().split(",");
                                if (hsb.length == 3) {
                                    Float h = Float.valueOf(hsb[0]) / 360;
                                    Float s = Float.valueOf(hsb[1]) / 100;
                                    Float b = Float.valueOf(hsb[2]) / 100;
                                    casambiSocketCopy.setUnitHSB(deviceId, h, s, b);
                                } else {
                                    logger.info("handleCommand: illegal hsb value {}", command.toString());
                                }
                                commandHandled = true;
                            } else if (command instanceof OnOffType) {
                                casambiSocketCopy.setUnitOnOff(deviceId, command.equals(OnOffType.ON));
                                commandHandled = true;
                            }
                        } else if (LUMINAIRE_CHANNEL_CCT.equals(channelUID.getId())) {
                            // Set color temperature (e.g. 2000 - 6500)
                            logger.trace("handleCommand: got CCT channel command {}", command);
                            if (command instanceof DecimalType) {
                                final float slider = ((PercentType) command).floatValue() / 100;
                                final Float tMin = config.tempMin;
                                final Float tMax = config.tempMax;
                                final Float temp = tMin + (tMax - tMin) * slider;
                                casambiSocketCopy.setUnitCCT(deviceId, temp);
                                commandHandled = true;
                            }
                            // } else if (LUMINAIRE_CHANNEL_WHITELEVEL.equals(channelUID.getId())) {
                            // // Set color balance color (0-100) and white (0-100)
                            // logger.info("handleCommand: got WHITELEVEL channel command {}", command);
                            // if (command instanceof PercentType) {
                            // Float slider = ((PercentType) command).floatValue();
                            // casambiSocketCopy.setUnitWhitelevel(deviceId, slider);
                            // commandHandled = true;
                            // }
                        } else {
                            logger.info("handleCommand: unexpected channel id {}", channelUID.getId());
                        }
                        if (!commandHandled) {
                            logger.info("handleCommand: channel {}, unexpected command type {}", channelUID,
                                    command.getClass());
                        }
                    }
                } catch (Exception e) {
                    // FIXME: do a reopen here, if "remote endpoint not open"? Handle different types of errors?
                    logger.info("handleCommand: Exception {} for channel {}, command {}.", e.toString(),
                            channelUID.toString(), command.getClass());
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                            String.format("Channel %s, Command %s, Exception %s", channelUID.toString(),
                                    command.getClass(), e.toString()));
                }
                if (command instanceof RefreshType) {
                    // Send refresh command here
                    try {
                        logger.trace("handleCommand: uid {} get unit state", channelUID);
                        final CasambiSimpleMessageUnit unitState = casambiRestCopy.getUnitState(deviceId);
                        if (unitState != null) {
                            updateLuminaireState(unitState);
                        } else {
                            logger.debug("handleCommand: uid {}, unit state is null", channelUID);
                        }
                    } catch (Exception e) {
                        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, String.format(
                                "Channel %s Exception getting unit state %s", channelUID.toString(), e.toString()));
                    }
                }
            } else {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        String.format("Channel %s Handler not found ", channelUID.toString()));
            }
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    String.format("Channel %s Bridge not found ", channelUID.toString()));
        }
    }

    /**
     * initialize does the real work for setting up a luminaire
     *
     */
    @Override
    public void initialize() {
        // FIXME: the next 2 statements should not be needed. Values have already been set by the constructor
        deviceId = ((BigDecimal) this.thing.getConfiguration().get(LUMINAIRE_ID)).intValueExact();
        deviceUid = this.thing.getConfiguration().get(LUMINAIRE_UID).toString();

        final CasambiSimpleBridgeHandler bridgeHandler = getBridgeHandler();
        if (bridgeHandler != null) {

            bridgeHandler.thingsById.put(bridgeHandler.thingsById.uidIdCombine(deviceUid, deviceId), this.thing);
            logger.debug("initialize: uid {}, id {}, thingUid {}", deviceUid, deviceId, this.thing.getUID());

            // FIXME: This shouldn't be done on every initialization but once after as scan.

            // Get properties of the device
            final boolean hasBri = ((Boolean) true).equals(this.thing.getConfiguration().get(LUMINAIRE_HAS_DIMMER));
            final boolean hasCo = ((Boolean) true).equals(this.thing.getConfiguration().get(LUMINAIRE_HAS_COLOR));
            final boolean hasCoTe = ((Boolean) true).equals(this.thing.getConfiguration().get(LUMINAIRE_HAS_CCT));

            logger.trace("initialize: thing {}: hasBri {}, hasCo {}, hasCoTe {}", deviceUid, hasBri, hasCo, hasCoTe);

            // Remove channels that have no corresponding control in the device
            final ThingBuilder tb = editThing();
            for (Channel ch : this.thing.getChannels()) {
                logger.trace("Thing {} has channel: uid {}, label {} type {}", deviceUid, ch.getUID(), ch.getLabel(),
                        ch.getChannelTypeUID());
                if (LUMINAIRE_CHANNEL_DIMMER.equals(ch.getUID().getId())) {
                    if (!hasBri) {
                        logger.trace("Thing: {} removing DIMMER channel {}", deviceUid, ch.getUID());
                        tb.withoutChannel(ch.getUID());
                    }
                }
                if (LUMINAIRE_CHANNEL_COLOR.equals(ch.getUID().getId())) {
                    if (!hasCo) {
                        logger.trace("Thing: {} removing COLOR channel {}", deviceUid, ch.getUID());
                        tb.withoutChannel(ch.getUID());
                    }
                }
                if (LUMINAIRE_CHANNEL_CCT.equals(ch.getUID().getId())) {
                    if (!hasCoTe) {
                        logger.trace("Thing: {} Removing CCT channel {}", deviceUid, ch.getUID());
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
    public void handleRemoval() {
        logger.debug("handleRemoval: removing luminaireid {}, uid {} from thingsById", this.deviceId, this.deviceUid);
        final CasambiSimpleBridgeHandler bridgeHandler = getBridgeHandler();
        if (bridgeHandler != null) {
            CasambiSimpleThingsById thingsById = bridgeHandler.thingsById;
            thingsById.remove(thingsById.uidIdCombine(deviceUid, deviceId));
        }
        updateStatus(ThingStatus.REMOVED);
    }

    @Override
    public void dispose() {
        logger.trace("dispose: dispose luminaire handler id {}, uid {}.", this.deviceId, this.deviceUid);
        super.dispose();
    }

    /**
     * bridgeStatusChanged informs a thing about a change in the bridge status. The thing status
     * is updated accordingly
     *
     * @param info the current bridge status
     */
    @Override
    public void bridgeStatusChanged(ThingStatusInfo info) {
        logger.debug("bridgeStatusChanged: {}, updating luminaire {}", info, deviceId);
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
                logger.info("bridgeStatusChanged: unexpected bridge status {}", bridgeStatus);
            }
        }
    }

    // --- Instance methods ----------------------------------------------------------------------------------

    /**
     * getBridgeHandler returns the handler for the luminaires bridge
     *
     * @return bridge handler or null (should not happen)
     */
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

    /**
     * update state updates the state of a channel (not really needed)
     *
     * @param chan channel to be updated
     * @state state of the channel
     */
    @Override
    public void updateState(ChannelUID chan, State state) {
        logger.trace("updateState: channel {}, state {}", chan, state);
        super.updateState(chan, state);
    }

    /**
     * updateLuminaireState updates the state of a luminaire and its dimmer channel
     *
     * @param state unit structure with state information
     *            FIXME: update other channels as well
     */
    public void updateLuminaireState(CasambiSimpleMessageUnit state) {
        if (state.online != null) {
            boolean ol = state.online;
            logger.trace("updateLuminaireState: id {} online {}", deviceId, ol);
            if (ol) {
                updateStatus(ThingStatus.ONLINE);
            } else {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        String.format("Unit %d status offline", deviceId));
            }
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    String.format("Unit %d state.online is null", deviceId));

        }
        if (state.dimLevel != null) {
            updateState(LUMINAIRE_CHANNEL_DIMMER, new PercentType(Math.round(state.dimLevel * 100)));
            logger.trace("updateLuminaireState: id {} dimLevel {}", deviceId, state.dimLevel);
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    String.format("Unit %d state.dimLevel is null", deviceId));
        }
    }

    /**
     * updateLuminaireStatus makes updateState public
     *
     * @param t is the luminaires status
     */
    public void updateLuminaireStatus(ThingStatus t) {
        updateStatus(t);
    }

    /**
     * Map Luminaire uids to things. Needed to update thing status based on casambi message content and for discovery
     *
     * @return the luminaires uid (e.g. "lum1234")
     */
    @Nullable
    public String getUid() {
        final Object uid = this.thing.getConfiguration().getProperties().get(LUMINAIRE_UID);
        if (uid != null) {
            return uid.toString();
        } else {
            return null;
        }
    }

    // --- Static methods ----------------------------------------------------------------------------------

    /**
     * getUidFromFixtureId builds the luminaires id based on the (hopefully locally unique fixtureId)
     *
     * @param fixtureId from the casambi unit information
     * @return Uid as string
     */
    public static String getUidFromFixtureId(Integer fixtureId) {
        return "lum" + fixtureId.toString();
    }
}

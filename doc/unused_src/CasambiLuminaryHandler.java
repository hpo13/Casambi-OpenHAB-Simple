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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

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
import org.openhab.core.thing.DefaultSystemChannelTypeProvider;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.binding.BridgeHandler;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
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
        putThingById(UnitUidIdSet.uidIdCombine(deviceUid, deviceId));
        logger.info("constructor: luminary uid {}, id {}", deviceUid, deviceId);
    }

    // --- Overridden superclass methods ---------------------------------------------------------------------------

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        logger.debug("handleCommand: channel uid {}, command {}", channelUID, command);
        CasambiBridgeHandler bridgeHandler = getBridgeHandler();
        Boolean doRefresh = false;
        if (bridgeHandler != null && bridgeHandler.casambiSocket != null) {
            if (LUMINARY_CHANNEL_ONOFF.equals(channelUID.getId())) {
                // Set dim level (0-100)
                if (command instanceof RefreshType) {
                    doRefresh = true;
                } else if (command instanceof OnOffType) {
                    try {
                        bridgeHandler.casambiSocket.setUnitDimmer(deviceId, command.equals(OnOffType.ON) ? 1 : 0);
                    } catch (Exception e) {
                        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                                String.format("Channel %s Exception %s", channelUID.toString(), e.toString()));
                    }
                } else {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                            String.format("Channel %s Illegal command %s", channelUID.toString(), command.toString()));
                }
            } else if (LUMINARY_CHANNEL_DIMMER.equals(channelUID.getId())) {
                // Set dim level (0-100)
                if (command instanceof RefreshType) {
                    doRefresh = true;
                } else if (command instanceof PercentType) {
                    try {
                        bridgeHandler.casambiSocket.setUnitDimmer(deviceId, ((PercentType) command).floatValue() / 100);
                    } catch (Exception e) {
                        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                                String.format("Channel %s Exception %s", channelUID.toString(), e.toString()));
                    }
                } else {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                            String.format("Channel %s Illegal command %s", channelUID.toString(), command.toString()));
                }
            } else if (LUMINARY_CHANNEL_COLOR.equals(channelUID.getId())) {
                // Set hue (0-360), saturation (0-100) and brightness (0-100)
                logger.info("handleCommand: got color channel command {}", command);
                if (command instanceof RefreshType) {
                    doRefresh = true;
                } else if (command instanceof HSBType) {
                    try {
                        String[] hsb = ((HSBType) command).toString().split(",");
                        if (hsb.length == 3) {
                            Float h = Float.valueOf(hsb[0]) / 360;
                            Float s = Float.valueOf(hsb[1]) / 100;
                            Float b = Float.valueOf(hsb[2]) / 100;
                            bridgeHandler.casambiSocket.setUnitHSB(deviceId, h, s, b);
                        } else {
                            logger.warn("handleCommand: illegal hsb value {}", command.toString());
                        }
                    } catch (Exception e) {
                        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                                String.format("Channel %s Exception %s", channelUID.toString(), e.toString()));
                    }
                } else if (command instanceof OnOffType) {
                    try {
                        if (command.equals(OnOffType.ON)) {
                            bridgeHandler.casambiSocket.setUnitDimmer(deviceId, 1);
                        } else {
                            bridgeHandler.casambiSocket.setUnitDimmer(deviceId, 0);
                        }
                    } catch (Exception e) {
                        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                                String.format("Channel %s Exception %s", channelUID.toString(), e.toString()));
                    }
                } else {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                            String.format("Channel %s Illegal command %s", channelUID.toString(), command.toString()));
                }
            } else if (LUMINARY_CHANNEL_CCT.equals(channelUID.getId())) {
                // Set color temperature (e.g. 2000 - 6500)
                logger.info("handleCommand: got cct channel command {}", command);
                if (command instanceof RefreshType) {
                    doRefresh = true;
                } else if (command instanceof DecimalType) {
                    try {
                        Float slider = ((PercentType) command).floatValue() / 100;
                        Float tMin = config.tempMin;
                        Float tMax = config.tempMax;
                        Float temp = tMin + (tMax - tMin) * slider;
                        bridgeHandler.casambiSocket.setUnitCCT(deviceId, temp);
                    } catch (Exception e) {
                        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                                String.format("Channel %s Exception %s", channelUID.toString(), e.toString()));
                    }
                } else {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                            String.format("Channel %s Illegal command %s", channelUID.toString(), command.toString()));
                }
            } else if (LUMINARY_CHANNEL_COLORBALANCE.equals(channelUID.getId())) {
                // Set color balance color (0-100) and white (0-100)
                logger.info("handleCommand: got colorbalance channel command {}", command);
                if (command instanceof RefreshType) {
                    doRefresh = true;
                } else if (command instanceof PercentType) {
                    try {
                        Float slider = ((PercentType) command).floatValue() / 100;
                        Float white = (float) 1;
                        Float color = (float) 1;
                        if (slider <= 0.5) {
                            color = 2 * slider;
                        } else {
                            white = 1 - 2 * (slider - (float) 0.5);
                        }
                        // bridgeHandler.casambiSocket.setUnitColorBalance(deviceId, white, color);
                        bridgeHandler.casambiSocket.setUnitColorBalance(deviceId, slider);
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
        Bridge bridge = getBridge();

        if (bridge == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "No bridge configured");
        } else {
            deviceId = ((BigDecimal) this.thing.getConfiguration().get(LUMINARY_ID)).intValueExact();
            deviceUid = this.thing.getConfiguration().get(LUMINARY_UID).toString();
            ThingUID thingUid = this.thing.getUID();
            putThingById(UnitUidIdSet.uidIdCombine(deviceUid, deviceId));
            logger.info("initialize: uid {}, id {}, thingUid {}", deviceUid, deviceId, thingUid);
            // ((CasambiBridgeHandler) bridge.getHandler()).logThingChannelConfig(this.thing);

            // FIXME: This shouldn't be done on every initialization but once after as scan

            // Get properties of the device
            // Boolean hasBri = (Boolean) this.thing.getConfiguration().get(LUMINARY_HAS_DIMMER);
            Object objBri = this.thing.getConfiguration().get(LUMINARY_HAS_DIMMER);
            Object objCo = this.thing.getConfiguration().get(LUMINARY_HAS_COLOR);
            Object objCoTe = this.thing.getConfiguration().get(LUMINARY_HAS_CCT);
            Object objCoBa = this.thing.getConfiguration().get(LUMINARY_HAS_COLORBALANCE);

            logger.info("Thing {}: hasBri {}, hasCo {}, hasCoTe {}, hasCoBa {}", deviceUid, objBri, objCo, objCoTe,
                    objCoBa);

            boolean hasBri = (objBri != null && (boolean) objBri == true) ? true : false;
            boolean hasCo = (objCo != null && (boolean) objCo == true) ? true : false;
            boolean hasCoTe = (objCoTe != null && (boolean) objCoTe == true) ? true : false;
            boolean hasCoBa = (objCoBa != null && (boolean) objCoBa == true) ? true : false;

            Channel chanSwi = null;
            Channel chanBri = null;
            Channel chanCo = null;
            Channel chanCoTe = null;
            Channel chanCoBa = null;

            // Find the channels that are defined
            for (Channel ch : this.thing.getChannels()) {
                logger.info("Thing {} has channel: uid {}, label {} type {}", deviceUid, ch.getUID(), ch.getLabel(),
                        ch.getChannelTypeUID());
                if (DefaultSystemChannelTypeProvider.SYSTEM_CHANNEL_TYPE_UID_POWER.equals(ch.getUID().getId())) {
                    logger.info("Found SWITCH channel {}", ch.getUID());
                    chanSwi = ch;
                }
                if (DefaultSystemChannelTypeProvider.SYSTEM_CHANNEL_TYPE_UID_BRIGHTNESS.equals(ch.getUID().getId())) {
                    logger.info("Found DIMMER channel {}", ch.getUID());
                    chanBri = ch;
                }
                if (DefaultSystemChannelTypeProvider.SYSTEM_CHANNEL_TYPE_UID_COLOR.equals(ch.getUID().getId())) {
                    logger.info("Found COLOR channel {}", ch.getUID());
                    chanCo = ch;
                }
                if (DefaultSystemChannelTypeProvider.SYSTEM_CHANNEL_TYPE_UID_COLOR_TEMPERATURE
                        .equals(ch.getUID().getId())) {
                    logger.info("Found CCT channel {}", ch.getUID());
                    chanCoTe = ch;
                }
                if (LUMINARY_CHANNEL_COLORBALANCE.equals(ch.getUID().getId())) {
                    logger.info("Found COLORBALANCE channel {}", ch.getUID());
                    chanCoBa = ch;
                }
            }

            // Add the missing channels
            ThingBuilder tb = editThing();
            // if (chanSwi == null) {
            // ChannelUID cUid = new ChannelUID(this.thing.getUID(), LUMINARY_CHANNEL_SWITCH);
            // chanSwi = ChannelBuilder.create(cUid)
            // .withType(DefaultSystemChannelTypeProvider.SYSTEM_CHANNEL_TYPE_UID_POWER)
            // .withAcceptedItemType("SWITCH").withLabel("AutoSwitch")
            // .withDescription("Autogenerated switch channel for " + deviceUid).build();
            // tb.withChannel(chanSwi);
            // logger.info("Thing {} adding Swi channel {}", deviceUid, chanSwi.getUID());
            // }
            if (chanBri == null && hasBri) {
                ChannelUID cUid = new ChannelUID(this.thing.getUID(), LUMINARY_CHANNEL_DIMMER);
                chanBri = ChannelBuilder.create(cUid)
                        .withType(DefaultSystemChannelTypeProvider.SYSTEM_CHANNEL_TYPE_UID_BRIGHTNESS)
                        .withAcceptedItemType("DIMMER").withLabel("AutoBrightness")
                        .withDescription("Autogenerated brightness channel for " + deviceUid).build();
                tb.withChannel(chanBri);
                logger.info("Thing {} adding Bri channel {}", deviceUid, chanBri.getUID());
            }
            if (chanCo == null && hasCo) {
                ChannelUID cUid = new ChannelUID(this.thing.getUID(), LUMINARY_CHANNEL_COLOR);
                chanCo = ChannelBuilder.create(cUid)
                        .withType(DefaultSystemChannelTypeProvider.SYSTEM_CHANNEL_TYPE_UID_COLOR)
                        .withAcceptedItemType("COLOR").withLabel("AutoColor")
                        .withDescription("Autogenerated color channel for " + deviceUid).build();
                tb.withChannel(chanCo);
                logger.info("Thing {} adding Co channel {}", deviceUid, chanCo.getUID());
            }
            if (chanCoTe == null && hasCoTe) {
                ChannelUID cUid = new ChannelUID(this.thing.getUID(), LUMINARY_CHANNEL_CCT);
                chanCoTe = ChannelBuilder.create(cUid)
                        .withType(DefaultSystemChannelTypeProvider.SYSTEM_CHANNEL_TYPE_UID_COLOR_TEMPERATURE)
                        .withAcceptedItemType("DIMMER").withLabel("AutoColor")
                        .withDescription("Autogenerated color temperature channel for " + deviceUid).build();
                tb.withChannel(chanCoTe);
                logger.info("Thing {} adding CoTe channel {}", deviceUid, chanCoTe.getUID());
            }
            if (chanCoBa == null && hasCoBa) {
                ChannelUID cUid = new ChannelUID(this.thing.getUID(), LUMINARY_CHANNEL_COLORBALANCE);
                chanCoBa = ChannelBuilder.create(cUid)
                        .withType(DefaultSystemChannelTypeProvider.SYSTEM_CHANNEL_TYPE_UID_BRIGHTNESS)
                        .withAcceptedItemType("NUMBER").withLabel("AutoColorBalance")
                        .withDescription("Autogenerated color balance channel for " + deviceUid).build();
                tb.withChannel(chanCoBa);
                logger.info("Thing {} adding CoBa channel {}", deviceUid, chanCoBa.getUID());
            }
            updateThing(tb.build());

            updateStatus(ThingStatus.ONLINE);
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

    // Add a (new) thing to the mapping
    public void putThingById(@Nullable String uidId) {
        logger.trace("putThingById: uidId {}", uidId);
        if (uidId != null) {
            thingsByUidId.putIfAbsent(uidId, this.thing);
        }
    }

    public String getUid() {
        return this.thing.getConfiguration().getProperties().get(LUMINARY_UID).toString();
    }

    // --- Static methods ----------------------------------------------------------------------------------

    // Mapping from uids to things
    private static Map<String, Thing> thingsByUidId = new HashMap<>();

    // Get thing corresponding to uidId
    public static @Nullable Thing getThingByUidId(@Nullable String uidId) {
        if (uidId != null) {
            return thingsByUidId.get(uidId);
        } else {
            return null;
        }
    }

    public static @Nullable Thing getFirstThingById(@Nullable Integer id) {
        if (id != null) {
            for (Entry<String, Thing> uidIdThing : thingsByUidId.entrySet()) {
                String uid = uidIdThing.getKey();
                // UnitUidIdSet.logger.debug("getFirstThingById: looking for {}, got {}", id, uid);
                if (UnitUidIdSet.getId(uid) == id) {
                    return uidIdThing.getValue();
                }
            }
        }
        UnitUidIdSet.logger.info("getFirstThingById: nothing found for {} in {}", id, thingsByUidId.keySet());
        return null;
    }

    public static String getUidFromFixtureId(Integer fixtureId) {
        return "lum" + fixtureId.toString();
    }

    // --- Inner class UnitUidSet --------------------------------------------------------------------------------
    // --- UnitUidIdSet manages uid (fixture) - id (unit) combinations, needed for discovey

    public static class UnitUidIdSet {

        // Instance stuff

        private Set<String> oldThings = new HashSet<>();
        private Set<String> newThings = new HashSet<>();

        UnitUidIdSet() {
            for (Entry<String, Thing> mapping : thingsByUidId.entrySet()) {
                oldThings.add(mapping.getKey());
            }
            logger.trace("UidIdSet: constructor oldThings {}", oldThings);
        }

        public Set<String> getOldThings() {
            return oldThings;
        }

        public Set<String> getNewThings() {
            return newThings;
        }

        public void updateOldNew(String uid, Integer id) {
            String uidId = uidIdCombine(uid, id);
            if (oldThings.contains(uidId)) {
                logger.trace("updateOldNew: uid {} matches, removing from oldThings", uidId);
                oldThings.remove(uidId);
            } else {
                logger.trace("updateOldNew: uid {} does not match, adding to newThings", uidId);
                newThings.add(uidId);
            }
        }

        // Static stuff - convert uid/id ti uidId and uidId to id

        private static final Logger logger = LoggerFactory.getLogger(UnitUidIdSet.class);

        public static Integer getId(String uidId) {
            String[] u = uidId.split(":");
            return Integer.parseInt(u[1]);
        }

        public static String uidIdCombine(String uid, Integer id) {
            return String.format("%s:%d", uid, id);
        }
    }
}
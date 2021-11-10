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
import org.openhab.core.library.types.HSBType;
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
 * @version V0.5 211105@hpo Discovery working (with removal), added class to handle uid-id combinations
 */
@NonNullByDefault
public class CasambiLuminaryHandler extends BaseThingHandler {

    private final Logger logger = LoggerFactory.getLogger(CasambiLuminaryHandler.class);

    private Integer deviceId = 0;
    private String deviceUid = "";

    // --- Constructor ---------------------------------------------------------------------------------------------

    public CasambiLuminaryHandler(Thing thing) {
        super(thing);
        logger.trace("constructor: luminary uid {}, id {}", thing.getUID(), thing.getConfiguration().get(LUMINARY_ID));
    }

    // --- Overridden superclass methods ---------------------------------------------------------------------------

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        logger.debug("handleCommand: channel uid {}, command {}", channelUID, command);
        CasambiBridgeHandler bridgeHandler = getBridgeHandler();
        Boolean doRefresh = false;
        if (bridgeHandler != null && bridgeHandler.casambi != null) {
            if (LUMINARY_CHANNEL_SWITCH.equals(channelUID.getId())) {
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
            } else if (LUMINARY_CHANNEL_COLOR.equals(channelUID.getId())) {
                if (command instanceof RefreshType) {
                    doRefresh = true;
                } else if (command instanceof HSBType) {
                    try {
                        String[] hsb = ((HSBType) command).toString().split(",");
                        if (hsb.length == 3) {
                            Float h = Float.valueOf(hsb[0]) / 360;
                            Float s = Float.valueOf(hsb[1]) / 100;
                            Float b = Float.valueOf(hsb[2]) / 100;
                            bridgeHandler.casambi.setUnitHSB(deviceId, h, s, b);
                        } else {
                            logger.warn("handleCommand: illegal hsb value {}", command.toString());
                        }
                    } catch (Exception e) {
                        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                                String.format("Channel %s Exception %s", channelUID.toString(), e.toString()));
                    }
                } else {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                            String.format("Channel %s Illegal command %s", channelUID.toString(), command.toString()));
                }
            } else if (LUMINARY_CHANNEL_DIM.equals(channelUID.getId())) {
                logger.info("handleCommand: got color channel command {}", command);
                if (command instanceof RefreshType) {
                    doRefresh = true;
                } else if (command instanceof PercentType) {
                    try {
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
                    logger.trace("handleCommand: uid {} get unit state", channelUID);
                    CasambiMessageUnit unitState = bridgeHandler.casambi.getUnitState(deviceId);
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
            updateStatus(ThingStatus.ONLINE);
        }

        deviceId = ((BigDecimal) this.thing.getConfiguration().get(LUMINARY_ID)).intValueExact();
        deviceUid = this.thing.getConfiguration().get(LUMINARY_UID).toString();
        putThingById(UnitUidIdSet.uidIdCombine(deviceUid, deviceId));
        logger.trace("initialize: uid {}, id {}", deviceUid, deviceId);
    }

    @Override
    public void dispose() {
        logger.debug("dispose: dispose luninary handler id {}, uid {}. NOP!", this.deviceId, this.deviceUid);
        // FIXME: do the actual disposal
        // super.dispose();
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
        logger.trace("updateLuminaryState: id {} dimLevel {}", deviceId, lvl);
        if (state.online != null && state.online) {
            updateStatus(ThingStatus.ONLINE);
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    String.format("Unit %d status offline", deviceId));
        }
        if (state.dimLevel == 0) {
            // updateState(LUMINARY_CHANNEL_SWITCH, OnOffType.OFF);
            updateState(LUMINARY_CHANNEL_DIM, new PercentType(0));
        } else {
            // updateState(LUMINARY_CHANNEL_SWITCH, OnOffType.ON);
            updateState(LUMINARY_CHANNEL_DIM, new PercentType(Math.round(lvl * 100)));
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
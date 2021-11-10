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

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.Future;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.casambitest.internal.driver.CasambiDriverJson;
import org.openhab.binding.casambitest.internal.driver.CasambiDriverSystem;
import org.openhab.binding.casambitest.internal.driver.messages.CasambiMessageEvent;
import org.openhab.binding.casambitest.internal.driver.messages.CasambiMessageEvent.messageType;
import org.openhab.binding.casambitest.internal.driver.messages.CasambiMessageNetwork;
import org.openhab.binding.casambitest.internal.driver.messages.CasambiMessageNetworkState;
import org.openhab.binding.casambitest.internal.driver.messages.CasambiMessageScene;
import org.openhab.binding.casambitest.internal.driver.messages.CasambiMessageSession;
import org.openhab.binding.casambitest.internal.driver.messages.CasambiMessageUnit;
import org.openhab.binding.casambitest.internal.handler.CasambiLuminaryHandler.UnitUidIdSet;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.thing.binding.BridgeHandler;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link CasambiBridgeHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Hein Osenberg - Initial contribution
 * @version V0.1 210827@hpo First version, setup IDE
 * @version V0.2 210829@hpo All methods from casambi-master implemented, exception, logging added
 * @version V0.3 211010@hpo First version to actually control lights
 * @version V0.4 211016@hpo Concurrency reworked, socket error checking
 * @version V0.5 211105@hpo Discovery works (with removal)
 */
@NonNullByDefault
public class CasambiBridgeHandler extends BaseBridgeHandler {

    private final Logger logger = LoggerFactory.getLogger(CasambiBridgeHandler.class);

    private @Nullable CasambiBridgeConfiguration config;

    public @Nullable CasambiDriverJson casambi;
    private @Nullable CasambiDiscoveryService discover;

    private @Nullable Future<?> pollMessageJob;
    private @Nullable Future<?> sendKeepAliveJob;
    private @Nullable Future<?> pollUnitStatusJob;
    private @Nullable Future<?> peerRecoveryJob;

    private volatile int missedPong = 0;
    private volatile Boolean bridgeOnline = false;
    private volatile Boolean recoveryInProgress = false;

    @SuppressWarnings("unused")
    private @Nullable CasambiMessageSession userSession;
    @SuppressWarnings("unused")
    private @Nullable Map<String, CasambiMessageNetwork> networkSession;

    // --- Constructor ---------------------------------------------------------------------------------------------

    public CasambiBridgeHandler(Bridge bridge) {
        super(bridge);
        config = getConfigAs(CasambiBridgeConfiguration.class);
        casambi = new CasambiDriverJson(config.apiKey, config.userId, config.userPassword, config.networkPassword);
        casambi.setupJsonLogging(config.logMessages, config.logDir, "casambiJsonMessages.txt");
    };

    @Override
    public Collection<Class<? extends ThingHandlerService>> getServices() {
        return Collections.singleton(CasambiDiscoveryService.class);
    }

    // --- Override superclass methods--------------------------------------------------------------------

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        logger.debug("handleCommand: (Bridge) channel uid {}, command {}", channelUID, command);
        if (casambi != null) {
            if (command instanceof RefreshType) {
                logger.trace("handleCommand: (Bridge) doRefresh NOP");
            }
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    String.format("CasambiDriver is null, channel ", channelUID.toString()));
        }
    };

    @Override
    public void initialize() {
        logger.debug("initialize: setting up bridge");
        BridgeHandler bridgeHandler = this.getThing().getHandler();
        if (bridgeHandler != null) {
            discover.setThingHandler(bridgeHandler);
        }
        scheduler.execute(initCasambiSession);
        pollMessageJob = scheduler.submit(handleCasambiMessages);
        sendKeepAliveJob = scheduler.submit(sendKeepAlive);
        pollUnitStatusJob = scheduler.submit(pollUnitStatus);

        CasambiDriverSystem.enableSshCommand(config.useRemCmd, config.remCmdStr);
    };

    @Override
    public void dispose() {
        logger.debug("dispose: tear down bridge");
        if (pollMessageJob != null) {
            pollMessageJob.cancel(true);
        }
        if (sendKeepAliveJob != null) {
            sendKeepAliveJob.cancel(true);
        }
        if (pollUnitStatusJob != null) {
            pollUnitStatusJob.cancel(true);
        }
        if (peerRecoveryJob != null) {
            peerRecoveryJob.cancel(true);
        }
        try {
            if (casambi != null) {
                casambi.casambiSocketClose();
            }
        } catch (Exception e) {
            logger.error("dispose: Exception {}", e.toString());
        }
        // casambi = null;
    };

    // Optional method
    @Override
    public void childHandlerInitialized(ThingHandler handler, Thing thing) {
        logger.trace("childHandlerInitialized: NOP!");
        // Das ist Quatsch hier - nur implementieren, wenn auch etwas sinnvolles passiert (mit dem thing!)
        // updateStatus(ThingStatus.ONLINE);
    };

    // Optional method
    @Override
    public void childHandlerDisposed(ThingHandler handler, Thing thing) {
        logger.trace("childHandlerDispose: NOP!");
        // Das ist Quatsch hier - nur implementieren, wenn auch etwas sinnvolles passiert (mit dem thing!)
        // updateStatus(ThingStatus.REMOVED);
    };

    // --- More instance methods --------------------------------------------------------------------------------

    public Boolean getBridgeStatus() {
        return bridgeOnline;
    }

    public void registerDiscoveryListener(CasambiDiscoveryService discoveryHandler) {
        discover = discoveryHandler;
    }

    public void unregisterDiscoveryListener() {
        discover = null;
    }

    // --- Runnable definitions ----------------------------------------------------------------------------------

    // Initiate casambi session.
    private Runnable initCasambiSession = new Runnable() {
        @Override
        public void run() {
            logger.debug("initCasambiSession: initializing session with casambi site");
            try {
                // Open session with casambi server
                if (casambi != null) {
                    userSession = casambi.createUserSession();
                    networkSession = casambi.createNetworkSession();
                    if (casambi.casambiSocketOpen()) {
                        bridgeOnline = true;
                        updateStatus(ThingStatus.ONLINE);
                        updateState(BRIDGE_CHANNEL_PEER, OnOffType.ON);
                        logger.debug("initCasambiSession: session initialized");
                    } else {
                        logger.error("initCasambiSession: Socket not open");
                        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                                "Error: Socket not open.");
                    }
                } else {
                    throw (new CasambiException("CasambiDriverJson is null"));
                }
            } catch (Exception e) {
                logger.error("initCasambiSession: Exception {}", e.toString());
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        "Exception during session initialisation: " + e.toString());
            }
        };
    };

    // Process messages from the casambi system
    private Runnable handleCasambiMessages = new Runnable() {
        @Override
        public void run() {
            logger.debug("handleCasambiMessages: starting runnable.");
            while (true) {
                if (Thread.interrupted()) {
                    logger.trace("handleCasambiMessages: got thread interrupt. Exiting job.");
                    return;
                }
                if (casambi != null) {
                    CasambiMessageEvent msg = casambi.receiveMessage();
                    if (msg != null) {
                        if (msg.getMessageType() != messageType.keepAlive) {
                            updateState(BRIDGE_CHANNEL_MESSAGE, StringType.valueOf(msg.toString()));
                        }
                        switch (msg.getMessageType()) {
                            case unitChanged:
                                if (msg.id != null && msg.on != null) {
                                    logger.debug("handleCasambiMessages: unitChanged id {}, online {}, on {}, dim {}",
                                            msg.id, msg.online, msg.on, msg.dimLevel);
                                    Thing thing = CasambiLuminaryHandler.getFirstThingById(msg.id);
                                    Float lvl = (msg.dimLevel != null) ? msg.dimLevel : 0;
                                    if (thing != null) {
                                        CasambiLuminaryHandler thingHandler = (CasambiLuminaryHandler) thing
                                                .getHandler();
                                        if (thingHandler != null) {
                                            // Update online status
                                            if (msg.online != null && msg.online) {
                                                thingHandler.updateLuminaryStatus(ThingStatus.ONLINE);
                                            } else {
                                                logger.warn("handleCasambiMessages: status OFFLINE, id {}", msg.id);
                                                thingHandler.updateLuminaryStatus(ThingStatus.OFFLINE);
                                            }
                                            // Update luminary state
                                            @Nullable
                                            Channel channel = thing.getChannel(LUMINARY_CHANNEL_SWITCH);
                                            if (channel != null) {
                                                thingHandler.updateState(channel.getUID(),
                                                        Math.round(lvl * 100) != 0 ? OnOffType.ON : OnOffType.OFF);
                                            }
                                            channel = thing.getChannel(LUMINARY_CHANNEL_DIM);
                                            if (channel != null) {
                                                thingHandler.updateState(channel.getUID(),
                                                        new PercentType(Math.round(lvl * 100)));
                                            }
                                        }
                                    }
                                }
                                break;
                            case peerChanged:
                                if (msg.online != null && msg.online) {
                                    logger.info("handleCasambiMessages: peer went online");
                                    updateState(BRIDGE_CHANNEL_PEER, OnOffType.ON);
                                    updateStatus(ThingStatus.ONLINE);
                                    bridgeOnline = true;
                                } else {
                                    logger.warn("handleCasambiMessages: peer went offline");
                                    updateState(BRIDGE_CHANNEL_PEER, OnOffType.OFF);
                                    updateStatus(ThingStatus.OFFLINE);
                                    bridgeOnline = false;
                                    if (!recoveryInProgress) {
                                        logger.info("handleCasambiMessages: trying to recover");
                                        // FIXME: check if peer recovery is enabled
                                        if (config.useRemCmd) {
                                            peerRecoveryJob = scheduler.submit(doPeerRecovery);
                                        }
                                    } else {
                                        logger.debug(
                                                "handleCasambiMessages: bridge still offline during recovery. Let's see.");
                                    }
                                }
                                break;
                            case networkUpdated:
                                logger.warn("handleCasambiMessages: networkUpdated online {}", msg.online);
                                // What to do: restart driver? rescan things and channels?
                                break;
                            case socketChanged: // Driver message
                                logger.warn("handleCasambiMessages: socketChanged, status {}, message {}", msg.status,
                                        msg.response);
                                break;
                            case wireStatusOk:
                                logger.trace("handleCasambiMessages: wireStatusOk: {}", msg.wireStatus);
                                break;
                            case wireStatusError:
                                logger.warn("handleCasambiMessages: wireStatusError: {}", msg.wireStatus);
                                break;
                            case keepAlive:
                                logger.trace("handleCasambiMessages: keepAlive got pong");
                                missedPong = 0;
                                break;
                            default:
                                logger.warn("handleCasambiMessages: unknown message type: {}", msg);
                        }
                    } else {
                        logger.warn("handleCasambiMessages: got null message.");
                        if (casambi != null) {
                            logger.debug("handleCasambiMessages: socketStatus {}", casambi.getSocketStatus());
                        }
                    }
                } else {
                    logger.warn("+++ handleCasambiMessages: casambi driver is null. Exiting.");
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Casambi driver is null");
                    return;
                }
            }
        };
    };

    // Send a ping message on the web socket to keep it open
    private Runnable sendKeepAlive = new Runnable() {
        @Override
        public void run() {
            logger.debug("sendKeepAlive: starting runnable.");
            while (true) {
                if (Thread.interrupted()) {
                    logger.trace("sendKeepAlive: got thread interrupt. Exiting job.");
                    return;
                }
                try {
                    Thread.sleep(280 * 1000);
                    if (missedPong > 0) {
                        logger.info("ping: Response missing for last ping.");
                        if (missedPong > 10) {
                            logger.warn("ping: {} ping responses missing. Sending recovery command.", missedPong);
                            CasambiDriverSystem.sendSshCommand();
                            missedPong = 0;
                        }
                    }
                    logger.trace("sendKeepAlive:");
                    if (casambi != null) {
                        casambi.ping();
                        missedPong++;
                    }
                } catch (Exception e) {
                    logger.error("sendKeepAlive: exception {}. Exiting.", e.getMessage());
                    return;
                }
            }
        }
    };

    // Do regular polls of the network state and adjust bridge and things accordingly
    private Runnable pollUnitStatus = new Runnable() {
        @Override
        public void run() {
            logger.debug("pollUnitStatus: starting runnable.");
            while (true) {
                if (Thread.interrupted()) {
                    logger.trace("pollUnitStatus: got thread interrupt. Exiting job.");
                    return;
                }
                try {
                    Thread.sleep(600 * 1000);
                    logger.trace("pollUnitStatus:");
                    if (casambi != null) {
                        CasambiMessageNetworkState networkState = casambi.getNetworkState();
                        if (networkState != null) {

                            // Get status of luminaries
                            if (networkState.units != null) {

                                for (Entry<Integer, CasambiMessageUnit> unit : networkState.units.entrySet()) {
                                    Thing thing = CasambiLuminaryHandler.getFirstThingById(unit.getKey());
                                    if (thing != null) {
                                        CasambiLuminaryHandler thingHandler = (CasambiLuminaryHandler) thing
                                                .getHandler();
                                        CasambiMessageUnit unitState = unit.getValue();
                                        if (thingHandler != null) {
                                            thingHandler.updateLuminaryState(unitState);
                                        }
                                    } else {
                                        logger.warn("pollUnitStatus: got status for unknown id {}, name {}",
                                                unit.getKey(), unit.getValue().name);
                                    }
                                }

                            } else {
                                logger.debug("pollUnitStatus: no units in network.");
                            }

                            // Get scene status
                            if (networkState.scenes != null) {

                                for (Entry<Integer, CasambiMessageScene> scene : networkState.scenes.entrySet()) {
                                    Thing thing = CasambiSceneHandler.getSceneById(scene.getKey());
                                    if (thing != null) {
                                        CasambiSceneHandler thingHandler = (CasambiSceneHandler) thing.getHandler();
                                        Channel channelScene = thing.getChannel(SCENE_CHANNEL_SCENE);

                                        if (networkState.activeScenes != null
                                                && Arrays.asList(networkState.activeScenes).contains(scene.getKey())) {
                                            logger.trace("pollUnitStatus: scene {} active", scene.getKey());
                                        } else {
                                            logger.trace("pollUnitStatus: scene {} inactive", scene.getKey());
                                            if (thingHandler != null && channelScene != null) {
                                                thingHandler.updateState(channelScene.getUID(), OnOffType.OFF);
                                            }
                                        }
                                    }
                                }

                            } else {
                                logger.debug("pollUnitStatus: no scenes in network.");
                            }

                        } else {
                            logger.warn("pollUnitStatus: got null network state message.");
                        }
                    } else {
                        logger.error("pollUnitStatus: CasambiDriverJson is null");
                    }
                } catch (Exception e) {
                    logger.error("pollUnitStatus: exception {}. Exiting.", e.getMessage());
                    return;
                }
            }
        };
    };

    // Discover devices
    public void scheduleDiscoveryScan() {
        logger.trace("scheduleDiscoveryScan:");
        scheduler.execute(new Runnable() {
            @Override
            public void run() {
                logger.trace("doDiscoveryScan: starting runnable.");
                try {
                    if (casambi != null) {
                        CasambiMessageNetworkState networkState = casambi.getNetworkState();
                        if (networkState != null) {
                            if (networkState.units != null && discover != null) {

                                // Initialize list of existing things and new units
                                CasambiLuminaryHandler.UnitUidIdSet oldNewThings = new CasambiLuminaryHandler.UnitUidIdSet();
                                Set<String> oldThings = oldNewThings.getOldThings();
                                logger.debug("doDiscoveryScan: oldThings before scan {}", oldThings);

                                // Loop through units removing from existing things and adding to new units
                                for (Entry<Integer, CasambiMessageUnit> unit : networkState.units.entrySet()) {
                                    Integer id = unit.getValue().id;
                                    Integer fixtureId = unit.getValue().fixtureId;
                                    String uid = CasambiLuminaryHandler.getUidFromFixtureId(fixtureId);
                                    logger.trace("doDiscoveryScan: got unit id {}, uid {}, name {}", id, uid,
                                            unit.getValue().name);
                                    oldNewThings.updateOldNew(uid, id);
                                }

                                // Add new units to things (using newThings)
                                Set<String> newThings = oldNewThings.getNewThings();
                                logger.debug("doDiscoveryScan: adding new things {}", newThings);

                                for (String uidId : newThings) {
                                    logger.trace("doDiscoveryScan: adding new unit uidId {}", uidId);
                                    Integer id = UnitUidIdSet.getId(uidId);
                                    CasambiMessageUnit unit = networkState.units.get(id);
                                    if (unit != null) {
                                        discover.addDiscoveredLuminary(unit);
                                    }
                                }

                                // Remove things without units (using oldThings)
                                oldThings = oldNewThings.getOldThings();
                                logger.debug("doDiscoveryScan: removing old things {}", oldThings);

                                for (String uidId : oldThings) {
                                    Thing thing = CasambiLuminaryHandler.getThingByUidId(uidId);
                                    if (thing != null) {
                                        logger.trace("doDiscoveryScan: removing old thing uidId {}", uidId);
                                        CasambiLuminaryHandler handler = (CasambiLuminaryHandler) thing.getHandler();
                                        // FIXME: Does this work?
                                        handler.dispose();
                                    } else {
                                        logger.debug("doDiscoveryScan: thing not found, cannot remove for uidId {}",
                                                uidId);
                                    }
                                }

                            } else {
                                logger.warn("doDiscoveryScan: no units in network or discovery not configured.");
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.error("doDiscoveryScan: exception {}. Exiting.", e.getMessage());
                    return;
                }
                logger.trace("doDiscoveryScan: done.");
            }
        });
    }

    // Try to reactivate the casambi app on the mobile phone
    private Runnable doPeerRecovery = new Runnable() {
        @Override
        public void run() {
            final int min = 60 * 1000;
            logger.trace("doPeerRecovery: starting runnable.");
            try {
                Thread.sleep(2 * min);
                if (bridgeOnline) {
                    logger.debug("doPeerRecovery: Step 1 - peer came back online. Recovery ended.");
                    recoveryInProgress = false;
                } else {
                    logger.debug("doPeerRecovery: Step 1 - peer offline. Sending command.");
                    CasambiDriverSystem.sendSshCommand();
                    Thread.sleep(3 * min);
                    if (bridgeOnline) {
                        logger.debug("doPeerRecovery: Step 2 - peer came back online. Recovery ended.");
                        recoveryInProgress = false;
                    } else {
                        logger.debug("doPeerRecovery: Step 2 - peer still offline. Sending command.");
                        CasambiDriverSystem.sendSshCommand();
                        Thread.sleep(6 * min);
                        if (bridgeOnline) {
                            logger.debug("doPeerRecovery: Step 3 - peer came back online.");
                            recoveryInProgress = false;
                        } else {
                            logger.debug("doPeerRecovery: Step 3 - peer still offline. Last try.");
                            CasambiDriverSystem.sendSshCommand();
                            Thread.sleep(2 * min);
                            if (bridgeOnline) {
                                logger.debug("doPeerRecovery: Step 4 - peer came back online. Just in time.");
                            } else {
                                logger.warn("doPeerRecovery: Step 4 - last try unsuccessful. Giving up.");
                            }
                            recoveryInProgress = false;
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("doPeerRecovery: exception {}. Exiting.", e.getMessage());
                return;
            }
        }
    };
}

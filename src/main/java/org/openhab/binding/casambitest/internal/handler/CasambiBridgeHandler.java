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
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Future;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.casambitest.internal.driver.CasambiDriverJson;
import org.openhab.binding.casambitest.internal.driver.CasambiDriverSystem;
import org.openhab.binding.casambitest.internal.driver.messages.CasambiMessageEvent;
import org.openhab.binding.casambitest.internal.driver.messages.CasambiMessageNetwork;
import org.openhab.binding.casambitest.internal.driver.messages.CasambiMessageNetworkState;
import org.openhab.binding.casambitest.internal.driver.messages.CasambiMessageScene;
import org.openhab.binding.casambitest.internal.driver.messages.CasambiMessageSession;
import org.openhab.binding.casambitest.internal.driver.messages.CasambiMessageUnit;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.thing.binding.ThingHandler;
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
 */
@NonNullByDefault
public class CasambiBridgeHandler extends BaseBridgeHandler {

    private final Logger logger = LoggerFactory.getLogger(CasambiBridgeHandler.class);

    public @Nullable CasambiDriverJson casambi;

    private @Nullable CasambiConfiguration config;
    @SuppressWarnings("unused")
    private @Nullable CasambiMessageSession userSession;
    @SuppressWarnings("unused")
    private @Nullable Map<String, CasambiMessageNetwork> networkSession;

    private @Nullable Future<?> pollMessageJob;
    private @Nullable Future<?> sendKeepAliveJob;
    private @Nullable Future<?> pollUnitStatusJob;
    private @Nullable Future<?> peerRecoveryJob;

    // This won't work, there are too many ways to change online status
    private volatile Boolean bridgeOnline = false;
    private volatile Boolean recoveryInProgress = false;

    // Read casambi messages from the ring buffer and update channel and thing status accordingly
    // private Runnable pollMessage = doPollMessage;

    public CasambiBridgeHandler(Bridge bridge) {
        super(bridge);
        config = getConfigAs(CasambiConfiguration.class);
        casambi = new CasambiDriverJson(config.apiKey, config.userId, config.userPassword, config.networkPassword);
        casambi.setupJsonLogging(config.logMessages, config.logDir, "casambiJsonMessages.txt");
    };

    // --- Override superclass methods--------------------------------------------------------------------

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        logger.debug("handleCommand: (Bridge) channel uid {}, command {}", channelUID, command);
        if (command instanceof RefreshType) {
            // TODO: handle data refresh
        }
    };

    @Override
    public void initialize() {
        logger.debug("initialize: setting up bridge");
        updateStatus(ThingStatus.UNKNOWN);
        CasambiDriverSystem.enableSshCommand(config.useRemCmd, config.remCmdStr);
        scheduler.execute(initCasambiSession);
        pollMessageJob = scheduler.submit(doPollMessage);
        sendKeepAliveJob = scheduler.submit(sendKeepAlive);
        pollUnitStatusJob = scheduler.submit(pollUnitStatus);
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
        casambi = null;
    };

    // Optional method
    @Override
    public void childHandlerInitialized(ThingHandler handler, Thing thing) {
        logger.debug("childHandlerInitialized: NOP");
        // Das ist Quatsch hier - nur implementieren, wenn auch etwas sinnvolles passiert (mit dem thing!)
        // updateStatus(ThingStatus.ONLINE);
    };

    // Optional method
    @Override
    public void childHandlerDisposed(ThingHandler handler, Thing thing) {
        logger.debug("childHandlerDispose: NOP");
        // Das ist Quatsch hier - nur implementieren, wenn auch etwas sinnvolles passiert (mit dem thing!)
        // updateStatus(ThingStatus.REMOVED);
    };

    // --- Thread definitions --------------------------------------------------------------------------------------

    // Initiate casambi session.
    private Runnable initCasambiSession = new Runnable() {
        // @SuppressWarnings("null")
        @Override
        public void run() {
            logger.debug("initCasambiSession: initializing session with casambi site");
            try {
                // Open session with casambi server
                if (casambi != null) {
                    userSession = casambi.createUserSession();
                    networkSession = casambi.createNetworkSession();
                    casambi.casambiSocketOpen();
                    bridgeOnline = true;
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
    private Runnable doPollMessage = new Runnable() {
        @Override
        public void run() {
            while (true) {
                if (Thread.interrupted()) {
                    logger.debug("pollCasambiMessages: got thread interrupt. Exiting job.");
                    return;
                }
                if (casambi != null) {
                    CasambiMessageEvent msg = casambi.receiveMessage();
                    if (msg != null) {
                        switch (msg.getMessageType()) {
                            case unitChanged:
                                if (msg.id != null && msg.on != null) {
                                    logger.debug("pollCasambiMessages: unitChanged id {}, online {}, on {}, dim {}",
                                            msg.id, msg.online, msg.on, msg.dimLevel);
                                    Thing thing = CasambiLuminaryHandler.getThingById(msg.id);
                                    Float lvl = (msg.dimLevel != null) ? msg.dimLevel : 0;
                                    if (thing != null) {
                                        CasambiLuminaryHandler thingHandler = (CasambiLuminaryHandler) thing
                                                .getHandler();
                                        if (thingHandler != null) {
                                            // Update online status
                                            if (msg.online != null && msg.online) {
                                                thingHandler.updateLuminaryStatus(ThingStatus.ONLINE);
                                            } else {
                                                logger.warn("pollCasambiMessages: status OFFLINE, id {}", msg.id);
                                                thingHandler.updateLuminaryStatus(ThingStatus.OFFLINE);
                                            }
                                            // Update luminary state
                                            @Nullable
                                            Channel channel = thing.getChannel(CHANNEL_SWITCH);
                                            if (channel != null) {
                                                thingHandler.updateState(channel.getUID(),
                                                        Math.round(lvl * 100) != 0 ? OnOffType.ON : OnOffType.OFF);
                                            }
                                            channel = thing.getChannel(CHANNEL_DIM);
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
                                    logger.info("pollCasambiMessages: peer went online");
                                    updateStatus(ThingStatus.ONLINE);
                                    bridgeOnline = true;
                                } else {
                                    logger.error("pollCasambiMessages: peer went offline");
                                    updateStatus(ThingStatus.OFFLINE);
                                    bridgeOnline = false;
                                    if (!recoveryInProgress) {
                                        logger.info("pollCasambiMessages: trying to recover");
                                        peerRecoveryJob = scheduler.submit(doPeerRecovery);
                                    } else {
                                        logger.debug(
                                                "pollCasambiMessages: bridge back online during recovery. Let's see.");
                                    }
                                }
                                break;
                            case networkUpdated:
                                logger.warn("pollCasambiMessages: networkUpdated online {}", msg.online);
                                // What to do: restart driver? rescan things and channels?
                                break;
                            case socketChanged: // Driver message
                                logger.warn("pollCasambiMessages: socketChanged, status {}, message {}", msg.status,
                                        msg.response);
                                break;
                            case wireStatusOk:
                                logger.debug("pollCasambiMessages: wireStatusOk: {}", msg.wireStatus);
                                break;
                            case wireStatusError:
                                logger.warn("pollCasambiMessages: wireStatusError: {}", msg.wireStatus);
                                break;
                            case keepAlive:
                                logger.trace("pollCasambiMessages: keepAlive got pong");
                                if (casambi != null) {
                                    casambi.pingOk();
                                }
                                break;
                            default:
                                logger.warn("pollCasambiMessages: unknown message type: {}", msg);
                        }
                    } else {
                        logger.warn("pollCasambiMessages: got null message.");
                        if (casambi != null) {
                            logger.debug("pollCasambiMessages: socketStatus {}", casambi.getSocketStatus());
                        }
                    }
                } else {
                    logger.warn("+++ pollCasambiMessages: casambi driver is null. Exiting.");
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
            while (true) {
                if (Thread.interrupted()) {
                    logger.debug("sendKeepAlive: got thread interrupt. Exiting job.");
                    return;
                }
                try {
                    Thread.sleep(280 * 1000);
                    logger.trace("sendKeepAlive:");
                    if (casambi != null) {
                        casambi.ping();
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
            while (true) {
                if (Thread.interrupted()) {
                    logger.debug("pollUnitStatus: got thread interrupt. Exiting job.");
                    return;
                }
                try {
                    Thread.sleep(600 * 1000);
                    logger.trace("pollUnitStatus:");
                    if (casambi != null) {
                        CasambiMessageNetworkState networkState = casambi.getNetworkState();
                        if (networkState != null) {
                            if (networkState.units != null) {
                                for (Entry<Integer, CasambiMessageUnit> unit : networkState.units.entrySet()) {
                                    Thing thing = CasambiLuminaryHandler.getThingById(unit.getKey());
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
                                logger.warn("pollUnitStatus: no units in network.");
                            }
                            if (networkState.scenes != null) {
                                for (Entry<Integer, CasambiMessageScene> scene : networkState.scenes.entrySet()) {
                                    Thing thing = CasambiSceneHandler.getSceneById(scene.getKey());
                                    if (thing != null) {
                                        CasambiSceneHandler thingHandler = (CasambiSceneHandler) thing.getHandler();
                                        Channel channelScene = thing.getChannel(CHANNEL_SCENE);

                                        if (networkState.activeScenes != null
                                                && Arrays.asList(networkState.activeScenes).contains(scene.getKey())) {
                                            logger.debug("pollUnitStatus: scene {} active", scene.getKey());
                                        } else {
                                            logger.debug("pollUnitStatus: scene {} inactive", scene.getKey());
                                            if (thingHandler != null && channelScene != null) {
                                                thingHandler.updateState(channelScene.getUID(), OnOffType.OFF);
                                            }
                                        }
                                    }
                                }
                            } else {
                                logger.warn("pollUnitStatus: no scenes in network.");
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

    // Try to reactivate the casambi app on the mobile phone
    private Runnable doPeerRecovery = new Runnable() {
        @Override
        public void run() {
            try {
                Thread.sleep(60 * 1000);
                if (bridgeOnline) {
                    logger.debug("doPeerRecovery: Step 1 - peer came back online. Recovery ended.");
                    recoveryInProgress = false;
                } else {
                    logger.debug("doPeerRecovery: Step 1 - peer offline. Sending command.");
                    CasambiDriverSystem.sendSshCommand();
                    Thread.sleep(120 * 1000);
                    if (bridgeOnline) {
                        logger.debug("doPeerRecovery: Step 2 - peer came back online. Recovery ended.");
                        recoveryInProgress = false;
                    } else {
                        logger.debug("doPeerRecovery: Step 2 - peer still offline. Sending command.");
                        CasambiDriverSystem.sendSshCommand();
                        Thread.sleep(300 * 1000);
                        if (bridgeOnline) {
                            logger.debug("doPeerRecovery: Step 3 - peer came back online. Just in time.");
                            recoveryInProgress = false;
                        } else {
                            logger.debug("doPeerRecovery: Step 3 - peer still offline. Giving up.");
                            Thread.sleep(600 * 1000);
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

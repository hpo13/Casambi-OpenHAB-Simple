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

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Map.Entry;
import java.util.concurrent.Future;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.openhab.binding.casambisimple.internal.driver.CasambiDriverLogger;
import org.openhab.binding.casambisimple.internal.driver.CasambiDriverRest;
import org.openhab.binding.casambisimple.internal.driver.CasambiDriverSocket;
import org.openhab.binding.casambisimple.internal.driver.CasambiDriverSystem;
import org.openhab.binding.casambisimple.internal.driver.messages.CasambiMessageEvent;
import org.openhab.binding.casambisimple.internal.driver.messages.CasambiMessageEvent.messageType;
import org.openhab.binding.casambisimple.internal.driver.messages.CasambiMessageNetworkState;
import org.openhab.binding.casambisimple.internal.driver.messages.CasambiMessageScene;
import org.openhab.binding.casambisimple.internal.driver.messages.CasambiMessageUnit;
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
 * The {@link CasambiBridgeHandler} manages to connection to the Casambi system
 * It sets up the connection, processes messages from the system, regularly polls
 * the system status and handles keepalive and recovery.
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

    private CasambiBridgeConfiguration config;

    public @Nullable CasambiDriverRest casambiRest;
    public @Nullable CasambiDriverSocket casambiSocket;
    public @Nullable CasambiDriverLogger messageLogger;
    private @Nullable CasambiDiscoveryService casambiDiscover;

    private @Nullable Future<?> pollMessageJob;
    private @Nullable Future<?> socketKeepAliveJob;
    private @Nullable Future<?> pollUnitStatusJob;
    private @Nullable Future<?> peerRecoveryJob;

    private volatile int missedPong = 0;
    private volatile Boolean bridgeOnline = false;
    private volatile Boolean recoveryInProgress = false;
    private final HttpClient httpClient;
    private final WebSocketClient webSocketClient;

    // FIXME: move this to the bridge handler. Then it needn't be static
    public CasambiThingsById thingsById = new CasambiThingsById();

    // private @Nullable CasambiMessageSession userSession;
    // private @Nullable Map<String, CasambiMessageNetwork> networkSession;

    // --- Constructor ---------------------------------------------------------------------------------------------

    public CasambiBridgeHandler(Bridge bridge, WebSocketClient webSocketClient, HttpClient httpClient) {
        super(bridge);
        this.httpClient = httpClient;
        this.webSocketClient = webSocketClient;
        config = getConfigAs(CasambiBridgeConfiguration.class);
    }

    @Override
    public Collection<Class<? extends ThingHandlerService>> getServices() {
        return Collections.singleton(CasambiDiscoveryService.class);
    }

    // --- Override superclass methods--------------------------------------------------------------------

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        logger.debug("handleCommand: (Bridge) channel uid {}, command {}", channelUID, command);
        CasambiDriverSocket casambiSocketLocal = casambiSocket;
        if (casambiSocketLocal != null) {
            if (command instanceof RefreshType) {
                logger.trace("handleCommand: (Bridge) doRefresh NOP");
            } else if (LUMINARY_CHANNEL_DIMMER.equals(channelUID.getId())) {
                // Set dim level (0-100)
                if (command instanceof PercentType) {
                    try {
                        casambiSocketLocal.setNetworkLevel(((PercentType) command).floatValue() / 100);
                    } catch (Exception e) {
                        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                                String.format("Channel %s Exception %s", channelUID.toString(), e.toString()));
                    }
                } else {
                    logger.warn("handleCommand: channel {}, unexpected command type {}", channelUID,
                            command.getClass());
                }
            } else {
                logger.warn("handleCommand: unexpected channel {}, command {}", channelUID, command.getClass());
            }
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    String.format("CasambiSocket is null, channel ", channelUID.toString()));
        }
    }

    @Override
    public void initialize() {
        logger.debug("initialize: setting up bridge");

        // Setup REST & WebSocket interface and start jobs
        scheduler.execute(initCasambiSession);

        // Discovery Handler
        BridgeHandler bridgeHandler = this.getThing().getHandler();
        if (bridgeHandler != null && casambiDiscover != null) {
            casambiDiscover.setThingHandler(bridgeHandler);
            logger.debug("initialize: setting bridgeHandler in discovery handler");
        }
        // logThingChannelConfig(this.thing);
    }

    @Override
    public void dispose() {
        logger.debug("dispose: tear down bridge");
        if (pollMessageJob != null) {
            pollMessageJob.cancel(true);
        }
        if (socketKeepAliveJob != null) {
            socketKeepAliveJob.cancel(true);
        }
        if (pollUnitStatusJob != null) {
            pollUnitStatusJob.cancel(true);
        }
        if (peerRecoveryJob != null) {
            peerRecoveryJob.cancel(true);
        }
        try {
            if (casambiSocket != null) {
                casambiSocket.close();
                casambiSocket = null;
            }
        } catch (Exception e) {
            logger.error("dispose: Exception {}", e.toString());
        }
        if (casambiRest != null) {
            casambiRest.close();
            casambiRest = null;
        }
        super.dispose();
    }

    // Optional method
    @Override
    public void childHandlerInitialized(ThingHandler handler, Thing thing) {
        logger.trace("childHandlerInitialized: NOP!");
        // Das ist Quatsch hier - nur implementieren, wenn auch etwas sinnvolles passiert (mit dem thing!)
        // updateStatus(ThingStatus.ONLINE);
    }

    // Optional method
    @Override
    public void childHandlerDisposed(ThingHandler handler, Thing thing) {
        logger.trace("childHandlerDispose: NOP!");
        // Das ist Quatsch hier - nur implementieren, wenn auch etwas sinnvolles passiert (mit dem thing!)
        // updateStatus(ThingStatus.REMOVED);
    }

    // --- More instance methods --------------------------------------------------------------------------------

    public Boolean getBridgeStatus() {
        return bridgeOnline;
    }

    public void registerDiscoveryListener(CasambiDiscoveryService discoveryHandler) {
        casambiDiscover = discoveryHandler;
    }

    public void unregisterDiscoveryListener() {
        casambiDiscover = null;
    }

    // --- Runnable definitions ----------------------------------------------------------------------------------

    // Initiate casambi session.
    private Runnable initCasambiSession = new Runnable() {
        @Override
        public void run() {
            logger.debug("initCasambiSession: initializing session with casambi site");
            try {

                messageLogger = new CasambiDriverLogger(config.logMessages, config.logDir, "casambiJsonMessages.txt");
                casambiRest = new CasambiDriverRest(config.apiKey, config.userId, config.userPassword,
                        config.networkPassword, messageLogger, webSocketClient, httpClient);
                CasambiDriverSystem.enableSshCommand(config.useRemCmd, config.remCmdStr);
                // logger.trace("initCasambiSession#1: after CasambiDriverRest constructor");
                // Open session with casambi server
                CasambiDriverRest casambiRestLocal = casambiRest;
                if (casambiRestLocal != null) {
                    casambiRestLocal.createUserSession();
                    // logger.trace("initCasambiSession#2: after createUserSession");
                    casambiRestLocal.createNetworkSession();
                    // logger.trace("initCasambiSession#3: after createNetworkSession");
                    pollUnitStatusJob = scheduler.submit(pollUnitStatus);
                    // logger.trace("initCasambiSession#4: after submit pollUnitStatus");

                    // WebSocket part
                    casambiSocket = casambiRestLocal.getSocket();
                    // logger.trace("initCasambiSession#5: CasambiDriverSocket constructor");
                    // FIXME: might be necessary to wait for casambiRemote to come up before proceeding
                    if (casambiSocket != null && casambiSocket.open()) {
                        // logger.trace("initCasambiSession#6: after Socket open");

                        bridgeOnline = true;
                        updateStatus(ThingStatus.ONLINE);
                        updateState(BRIDGE_CHANNEL_PEER, OnOffType.ON);

                        // pollMessageJob and socketKeepAlive need open socket
                        pollMessageJob = scheduler.submit(handleCasambiMessages);
                        // logger.trace("initCasambiSession#7: submit handleCasambiMessages");
                        socketKeepAliveJob = scheduler.submit(socketKeepAlive);
                        // logger.trace("initCasambiSession#7: submit socketKeepAlive");

                        logger.debug("initCasambiSession: session initialized");
                    } else {
                        logger.error("initCasambiSession: Socket not open");
                        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                                "Error: Socket not open.");
                    }
                } else {
                    logger.error("initCasambiSession: REST session not open");
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                            "Error: REST session not open.");
                }
            } catch (Exception e) {
                logger.error("initCasambiSession: Exception {}", e.getMessage());
                // e.printStackTrace();
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        "Exception during session initialisation: " + e.getMessage());
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
                if (casambiRest != null && casambiSocket != null) {
                    CasambiMessageEvent msg = casambiSocket.receiveMessage();
                    if (msg != null) {
                        if (msg.getMessageType() != messageType.keepAlive) {
                            updateState(BRIDGE_CHANNEL_MESSAGE, StringType.valueOf(msg.toString()));
                        }
                        switch (msg.getMessageType()) {
                            case unitChanged:
                                if (msg.id != null && msg.on != null) {
                                    logger.debug("handleCasambiMessages: unitChanged id {}, online {}, on {}, dim {}",
                                            msg.id, msg.online, msg.on, msg.dimLevel);

                                    Thing thing = thingsById.getFirstLuminary(msg.id);
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
                                            Channel channel = thing.getChannel(LUMINARY_CHANNEL_DIMMER);
                                            if (channel != null) {
                                                Float lvl = (msg.dimLevel != null) ? msg.dimLevel : 0;
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
                        if (casambiSocket != null) {
                            logger.debug("handleCasambiMessages: socketStatus {}", casambiSocket.getSocketStatus());
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
                    // Poll the unit status every 10 Minutes
                    Thread.sleep(600 * 1000);
                    logger.trace("pollUnitStatus:");
                    if (casambiRest != null) {
                        CasambiMessageNetworkState networkState = casambiRest.getNetworkState();
                        if (networkState != null) {

                            // Get status of luminaries
                            if (networkState.units != null) {

                                for (Entry<Integer, CasambiMessageUnit> unit : networkState.units.entrySet()) {
                                    Thing thing = thingsById.getFirstLuminary(unit.getKey());
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
                                    if (networkState.activeScenes != null
                                            && Arrays.asList(networkState.activeScenes).contains(scene.getKey())) {
                                        logger.trace("pollUnitStatus: scene {} active", scene.getKey());
                                    } else {
                                        logger.trace("pollUnitStatus: scene {} inactive", scene.getKey());
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
                    e.printStackTrace();
                    return;
                }
            }
        };
    };

    // Send a ping message on the web socket to keep it open
    private Runnable socketKeepAlive = new Runnable() {
        @Override
        public void run() {
            logger.debug("sendKeepAlive: starting runnable.");
            while (true) {
                if (Thread.interrupted()) {
                    logger.trace("sendKeepAlive: got thread interrupt. Exiting job.");
                    return;
                }
                try {
                    // Sleep for slightly less than 5 minutes
                    Thread.sleep(280 * 1000);
                    if (missedPong > 0) {
                        logger.info("ping: Response missing for last ping.");
                        // FIXME: will this help?
                        if (missedPong > 10) {
                            if (config.useRemCmd) {
                                logger.warn("ping: {} ping responses missing. Sending recovery command.", missedPong);
                                CasambiDriverSystem.sendSshCommand();
                                missedPong = 0;
                            }
                        }
                    }
                    logger.trace("sendKeepAlive:");
                    if (casambiSocket != null) {
                        casambiSocket.ping();
                        missedPong++;
                    }
                } catch (Exception e) {
                    logger.error("sendKeepAlive: exception {}. Exiting.", e.getMessage());
                    return;
                }
            }
        }
    };

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

    // public void logThingChannelConfig(Thing th) {
    // logger.info("logThingChannelConfig: {}, {}", th.getUID(), th.getLabel());
    // for (Channel ch : th.getChannels()) {
    // logChannelConfig(ch);
    // }
    // }
    //
    // public void logChannelConfig(Channel ch) {
    // logger.info("logChannelConfig: uid {}, typeUid {}, accItTyp {}, kind {}", ch.getUID(), ch.getChannelTypeUID(),
    // ch.getAcceptedItemType(), ch.getKind());
    // logger.info(" {}, label {}, desc {}, autoUpd {}", ch.getUID(), ch.getLabel(), ch.getDescription(),
    // ch.getAutoUpdatePolicy());
    // logger.info(" {}, dfltTags {}", ch.getUID(), ch.getDefaultTags());
    // logger.info(" {}, config {}", ch.getUID(), ch.getConfiguration());
    // logger.info(" {}, props {}", ch.getUID(), ch.getProperties());
    // }
}

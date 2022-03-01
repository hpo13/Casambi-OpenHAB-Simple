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

import static org.openhab.binding.casambisimple.internal.CasambiSimpleBindingConstants.*;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Future;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.openhab.binding.casambisimple.internal.driver.CasambiSimpleDriverLogger;
import org.openhab.binding.casambisimple.internal.driver.CasambiSimpleDriverRest;
import org.openhab.binding.casambisimple.internal.driver.CasambiSimpleDriverSocket;
import org.openhab.binding.casambisimple.internal.driver.CasambiSimpleDriverSystem;
import org.openhab.binding.casambisimple.internal.driver.CasambiSimpleException;
import org.openhab.binding.casambisimple.internal.driver.messages.CasambiSimpleMessageEvent;
import org.openhab.binding.casambisimple.internal.driver.messages.CasambiSimpleMessageEvent.messageType;
import org.openhab.binding.casambisimple.internal.driver.messages.CasambiSimpleMessageNetwork;
import org.openhab.binding.casambisimple.internal.driver.messages.CasambiSimpleMessageNetworkState;
import org.openhab.binding.casambisimple.internal.driver.messages.CasambiSimpleMessageScene;
import org.openhab.binding.casambisimple.internal.driver.messages.CasambiSimpleMessageSession;
import org.openhab.binding.casambisimple.internal.driver.messages.CasambiSimpleMessageUnit;
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
 * The {@link CasambiSimpleBridgeHandler} manages to connection to the Casambi system
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
public class CasambiSimpleBridgeHandler extends BaseBridgeHandler {

    private final Logger logger = LoggerFactory.getLogger(CasambiSimpleBridgeHandler.class);

    private CasambiSimpleBridgeConfiguration config;

    public @Nullable CasambiSimpleDriverRest casambiRest;
    public @Nullable CasambiSimpleDriverSocket casambiSocket;
    private @Nullable CasambiSimpleDiscoveryService casambiDiscover;

    private @Nullable Future<?> pollMessageJob;
    private @Nullable Future<?> socketKeepAliveJob;
    private @Nullable Future<?> pollUnitStatusJob;
    private @Nullable Future<?> peerRecoveryJob;
    // private @Nullable CasambiSimpleDriverLogger messageLogger;

    private volatile boolean bridgeOnline = false;
    private volatile boolean initSessionJobRunning = false;
    private volatile boolean peerRecoveryJobRunning = false;
    private volatile boolean pollUnitStatusJobRunning = false;
    private volatile boolean pollMessageJobRunning = false;
    private volatile boolean socketKeepAliveJobRunning = false;

    private volatile int missedPong = 0;
    private final HttpClient httpClient;
    private final WebSocketClient webSocketClient;

    private final int mSec = 1000;
    private final int min = 60 * mSec;

    // FIXME: move this to the bridge handler. Then it needn't be static
    public CasambiSimpleThingsById thingsById = new CasambiSimpleThingsById();

    // private @Nullable CasambiSimpleMessageSession userSession;
    // private @Nullable Map<String, CasambiSimpleMessageNetwork> networkSession;

    // --- Constructor ---------------------------------------------------------------------------------------------

    /**
     * CasambiSimpleBridgeHandler constructor instantiates the class for communication with the Casambi cloud service
     *
     * @param bridge - bridge as a thing
     * @param webSocketClient - from the webSocketFactory (OpenHAB infrastructure)
     * @param httpClient - from the httpClientFactory (OpenHAB infrastructure)
     *
     *            Just a couple of variable assignments. The actual initialization is done by the initialize() method
     */
    public CasambiSimpleBridgeHandler(Bridge bridge, WebSocketClient webSocketClient, HttpClient httpClient) {
        super(bridge);
        this.httpClient = httpClient;
        this.webSocketClient = webSocketClient;
        config = getConfigAs(CasambiSimpleBridgeConfiguration.class);
    }

    /**
     * getServices returns what exactly?
     */
    @Override
    public Collection<Class<? extends ThingHandlerService>> getServices() {
        return Collections.singleton(CasambiSimpleDiscoveryService.class);
    }

    // --- Override superclass methods--------------------------------------------------------------------

    /**
     * handleCommand handles channel commands to the bridge
     *
     * @param channelUID - currently only LUMINARY_CHANNEL_DIMM is supported for network wide dimming
     * @param command - currently dim level is the only meaningful command
     *            FIXME: maybe add channel for peer recovery and bridge reinitialization
     */
    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        logger.debug("handleCommand: (Bridge) channel uid {}, command {}", channelUID, command);
        CasambiSimpleDriverSocket casambiSocketLocal = casambiSocket;
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
                    String.format("CasambiSimpleSocket is null, channel ", channelUID.toString()));
        }
    }

    /**
     * initialize gets bridge initialization going. The actual work is done asynchronously with initCasambiSession.
     * Additionally, the
     * discovery service is set up
     */
    @Override
    public void initialize() {
        logger.debug("initialize: setting up bridge");

        // Setup REST & WebSocket interface and start jobs
        if (!initSessionJobRunning) {
            scheduler.execute(initCasambiSession);
        }

        // Discovery Handler
        BridgeHandler bridgeHandler = this.getThing().getHandler();
        CasambiSimpleDiscoveryService localCasambiDiscover = casambiDiscover;
        if (bridgeHandler != null && localCasambiDiscover != null) {
            localCasambiDiscover.setThingHandler(bridgeHandler);
            logger.debug("initialize: setting bridgeHandler in discovery handler");
        }
    }

    /**
     * dispose tears down the bridge including stopping jobs and closing socket etc.
     *
     * FIXME: need to unregister the discovery handler as well?
     */
    @Override
    public void dispose() {
        logger.debug("dispose: tear down bridge");
        if (pollMessageJob != null) {
            pollMessageJob.cancel(true);
            pollMessageJobRunning = false;
        }
        if (socketKeepAliveJob != null) {
            socketKeepAliveJob.cancel(true);
            socketKeepAliveJobRunning = false;
        }
        if (pollUnitStatusJob != null) {
            pollUnitStatusJob.cancel(true);
            pollUnitStatusJobRunning = false;
        }
        if (peerRecoveryJob != null) {
            peerRecoveryJob.cancel(true);
            peerRecoveryJobRunning = false;
        }
        try {
            if (casambiSocket != null) {
                casambiSocket.close();
            }
        } catch (Exception e) {
            logger.error("dispose: Exception {}", e.toString());
        } finally {
            casambiSocket = null;
        }
        if (casambiRest != null) {
            casambiRest.close();
            casambiRest = null;
        }
        bridgeOnline = false;
        super.dispose();
    }

    // Optional method, for development only, not production
    @Override
    public void childHandlerInitialized(ThingHandler handler, Thing thing) {
        logger.trace("childHandlerInitialized: NOP!");
        // Das ist Quatsch hier - nur implementieren, wenn auch etwas sinnvolles passiert (mit dem thing!)
        // updateStatus(ThingStatus.ONLINE);
    }

    // Optional method, for development only, not production
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

    public void registerDiscoveryListener(CasambiSimpleDiscoveryService discoveryHandler) {
        casambiDiscover = discoveryHandler;
    }

    public void unregisterDiscoveryListener() {
        casambiDiscover = null;
    }

    // --- Runnable definitions ----------------------------------------------------------------------------------

    /**
     * initCasambiSession - Initiate the Casambi session and the associated tasks
     *
     * Initializes the following objects (in order of appearance):
     * - messageLogger - needed, when Casambi messages are to be logged
     * - casambiRest with userSession and networkSession
     * - sshCommand for connection recovery
     * - pollUnitStatusJob (thread)
     * - casambiSocket (depends on casambiRest)
     * - bridgeOnline flag
     * - pollMessageJob (thread, for casambiSocket messages)
     * - socketKeepAliveJob (thread)
     * FIXME: maybe divide this into individual try...catch sections
     * FIXME: in case of error set the corresponding objects to null
     * FIXME: add "running" flags for threads
     */
    private Runnable initCasambiSession = new Runnable() {
        @Override
        public void run() {
            logger.debug("initCasambiSession: initializing Casambi session and jobs");
            initSessionJobRunning = true;

            if (pollUnitStatusJob != null) {
                pollUnitStatusJob.cancel(true);
                pollUnitStatusJobRunning = false;
            }
            if (pollMessageJob != null) {
                pollMessageJob.cancel(true);
                pollMessageJobRunning = false;
            }
            if (socketKeepAliveJob != null) {
                socketKeepAliveJob.cancel(true);
                socketKeepAliveJobRunning = false;
            }

            casambiSocket = null;
            casambiRest = null;
            bridgeOnline = false;

            try {
                // FIXME: append to datafile on reopen, variable file name (e.g. with date)
                CasambiSimpleDriverLogger messageLogger = new CasambiSimpleDriverLogger(config.logMessages,
                        config.logDir, "casambiJsonMessages.txt");
                messageLogger.dumpMessage("+++ initCasambiSession - logger started +++");
                casambiRest = new CasambiSimpleDriverRest(config.apiKey, config.userId, config.userPassword,
                        config.networkPassword, messageLogger, webSocketClient, httpClient);
                CasambiSimpleDriverSystem.enableSshCommand(config.useRemCmd, config.remCmdStr);
                CasambiSimpleDriverRest casambiRestLocal = casambiRest;
                if (casambiRestLocal != null) {
                    CasambiSimpleMessageSession userSession;
                    Map<String, CasambiSimpleMessageNetwork> networkSession;
                    if ((userSession = casambiRestLocal.createUserSession()) == null) {
                        throw new CasambiSimpleException("User session could not be initialized");
                    } else if ((networkSession = casambiRestLocal.createNetworkSession()) == null) {
                        throw new CasambiSimpleException("Network session could not be initialized");
                    } else {
                        if (!pollUnitStatusJobRunning) {
                            pollUnitStatusJob = scheduler.submit(pollUnitStatus);
                        }
                        casambiSocket = casambiRestLocal.getSocket();
                        if (/* casambiSocket != null && */ casambiSocket.open()) {
                            bridgeOnline = true;
                            updateStatus(ThingStatus.ONLINE);
                            updateState(BRIDGE_CHANNEL_PEER, OnOffType.ON);
                            if (!pollMessageJobRunning) {
                                pollMessageJob = scheduler.submit(handleCasambiMessages);
                            }
                            if (!socketKeepAliveJobRunning) {
                                socketKeepAliveJob = scheduler.submit(socketKeepAlive);
                            }
                            logger.debug("initCasambiSession: session initialized");
                        } else {
                            logger.error("initCasambiSession: Socket not open");
                            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                                    "Error: Socket not open.");
                            casambiSocket = null;
                        }
                    }
                } else {
                    logger.error("initCasambiSession: REST session not open");
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                            "Error: REST session not open.");
                    casambiRest = null;
                }
            } catch (Exception e) {
                logger.error("initCasambiSession: Exception {}", e.getMessage());
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        "Exception during session initialisation: " + e.getMessage());
            }
            initSessionJobRunning = false;
        }; // run()
    };

    /**
     * handleCasambiMessages processes messages from the Casambi system. Runs as long as the bridge is active.
     *
     * Mainly handles messages from the websocket interface. Replies from the http interface are handled by the REST
     * handler. The "socketChanged" message is directly from the driver, not from the Casambi cloud.
     *
     * The most important message type is "unitChanged" which updates the unit status of a thing or the state of a
     * channel. "peerChanged" monitors the bridge status and tries to restart the communication with the gateway if
     * necessary.
     */
    private Runnable handleCasambiMessages = new Runnable() {
        @Override
        public void run() {
            logger.debug("handleCasambiMessages: starting runnable.");
            pollMessageJobRunning = true;
            while (true) {
                if (Thread.interrupted()) {
                    logger.info("handleCasambiMessages: got thread interrupt. Exiting job.");
                    pollMessageJobRunning = false;
                    return;
                }
                if (casambiRest != null && casambiSocket != null) {
                    CasambiSimpleMessageEvent msg = casambiSocket.receiveMessage();
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
                                        CasambiSimpleLuminaryHandler thingHandler = (CasambiSimpleLuminaryHandler) thing
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
                                            // FIXME: update other channels as well
                                        }
                                    }
                                }
                                break;
                            case peerChanged:
                                if (msg.online != null && msg.online) {
                                    if (!bridgeOnline) {
                                        logger.info("handleCasambiMessages: peer went online");
                                        updateState(BRIDGE_CHANNEL_PEER, OnOffType.ON);
                                        updateStatus(ThingStatus.ONLINE);
                                        bridgeOnline = true;
                                    } else {
                                        logger.trace("handleCasambiMessages: extra online message");
                                    }
                                } else {
                                    logger.warn("handleCasambiMessages: peer went offline");
                                    updateState(BRIDGE_CHANNEL_PEER, OnOffType.OFF);
                                    updateStatus(ThingStatus.OFFLINE);
                                    bridgeOnline = false;
                                    if (!peerRecoveryJobRunning) {
                                        logger.info("handleCasambiMessages: trying to recover");
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
                                // FIXME: What to do: restart driver? rescan things and channels?
                                break;
                            case socketChanged: // Driver message
                                logger.info("handleCasambiMessages: socketChanged, status {}, message {}", msg.status,
                                        msg.response);
                                // FIXME: What to do?
                                break;
                            case wireStatusOk:
                                logger.trace("handleCasambiMessages: wireStatusOk: {}", msg.wireStatus);
                                break;
                            case wireStatusError:
                                logger.warn("handleCasambiMessages: wireStatusError: {}", msg.wireStatus);
                                // FIXME: What to do?
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
                    pollMessageJobRunning = false;
                    return;
                }
            }
        };
    };

    /**
     * pollUnitStatus does regular polls of the network state and adjusts channel state for bridge and things
     * accordingly. Runs as long as the bridge is active. Polling is done every 10 minutes (FIXME: should be
     * configurable).
     *
     * Currently only the state of luminaries is updated (by calling the appropriate thing handler).
     *
     * Scene and group status does not get updated. It is not quite clear, how the scene and group status can be defined
     * in a meaningful way.
     */
    private Runnable pollUnitStatus = new Runnable() {
        @Override
        public void run() {
            logger.debug("pollUnitStatus: starting runnable.");
            pollUnitStatusJobRunning = true;
            while (true) {
                if (Thread.interrupted()) {
                    logger.info("pollUnitStatus: got thread interrupt. Exiting job.");
                    pollUnitStatusJobRunning = false;
                    return;
                }
                try {
                    // Poll the unit status every 10 Minutes
                    Thread.sleep(600 * mSec);
                    logger.trace("pollUnitStatus:");
                    if (casambiRest != null) {
                        CasambiSimpleMessageNetworkState networkState = casambiRest.getNetworkState();
                        if (networkState != null) {

                            // Get status of luminaries
                            if (networkState.units != null) {

                                for (Entry<Integer, CasambiSimpleMessageUnit> unit : networkState.units.entrySet()) {
                                    Thing thing = thingsById.getFirstLuminary(unit.getKey());
                                    if (thing != null) {
                                        CasambiSimpleLuminaryHandler thingHandler = (CasambiSimpleLuminaryHandler) thing
                                                .getHandler();
                                        CasambiSimpleMessageUnit unitState = unit.getValue();
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
                                for (Entry<Integer, CasambiSimpleMessageScene> scene : networkState.scenes.entrySet()) {
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
                        logger.error("pollUnitStatus: CasambiDriverRest is null");
                        pollUnitStatusJobRunning = false;
                        return;
                    }
                } catch (Exception e) {
                    logger.warn("pollUnitStatus: exception {}. Continuing.", e.getMessage());
                    // logger.error("pollUnitStatus: exception {}. Exiting.", e.getMessage());
                    // pollUnitStatusJobRunning = false;
                    // return;
                }
            }
        };
    };

    /**
     * socketKeepAlive sends ping messages on the web socket to keep it open. Ping intervall is a bit less than 5
     * minutes (socket will close after 5 minutes). Runs as long as the bridge is active.
     */
    private Runnable socketKeepAlive = new Runnable() {
        @Override
        public void run() {
            logger.debug("sendKeepAlive: starting runnable.");
            socketKeepAliveJobRunning = true;
            while (true) {
                if (Thread.interrupted()) {
                    logger.info("sendKeepAlive: got thread interrupt. Exiting job.");
                    socketKeepAliveJobRunning = false;
                    return;
                }
                try {
                    // Sleep for slightly less than 5 minutes
                    Thread.sleep(280 * mSec);
                    if (missedPong > 0) {
                        logger.info("ping: Response missing for last ping.");
                        // FIXME: will this help?
                        if (missedPong > 10) {
                            if (config.useRemCmd) {
                                logger.warn("ping: {} ping responses missing. Sending recovery command.", missedPong);
                                CasambiSimpleDriverSystem.sendSshCommand();
                                missedPong = 0;
                            }
                        }
                    }
                    logger.trace("sendKeepAlive:");
                    if (casambiSocket != null) {
                        casambiSocket.ping();
                        missedPong++;
                    } else {
                        logger.error("sendKeepAlive: socket is null. Exiting.");
                        socketKeepAliveJobRunning = false;
                        return;
                    }
                } catch (Exception e) {
                    logger.warn("sendKeepAlive: exception {}. Continuing.", e.getMessage());
                    // logger.error("sendKeepAlive: exception {}. Exiting.", e.getMessage());
                    // socketKeepAliveJobRunning = false;
                    // return;
                }
            }
        }
    };

    /**
     * doPeerRecovery tries to reactivate the casambi app on the mobile phone with the sshCommand (if configured and
     * enabled). The runnable will be started on a peerChanged (offline) message.
     *
     * The recovery is tried three times with increasing intervals between recovery actions. If a peerChanged (online)
     * message is received (bridgeOnline) the job exits. The job exits as well after the third try.
     */
    private Runnable doPeerRecovery = new Runnable() {
        @Override
        public void run() {
            logger.info("doPeerRecovery: starting.");
            peerRecoveryJobRunning = true;
            try {
                Thread.sleep(2 * min);
                if (bridgeOnline) {
                    logger.info("doPeerRecovery: Step 1 - peer came back online. Recovery ended.");
                } else {
                    logger.debug("doPeerRecovery: Step 1 - peer offline. Sending command.");
                    CasambiSimpleDriverSystem.sendSshCommand();
                    Thread.sleep(3 * min);
                    if (bridgeOnline) {
                        logger.info("doPeerRecovery: Step 2 - peer came back online. Recovery ended.");
                    } else {
                        logger.debug("doPeerRecovery: Step 2 - peer still offline. Sending command.");
                        CasambiSimpleDriverSystem.sendSshCommand();
                        Thread.sleep(6 * min);
                        if (bridgeOnline) {
                            logger.info("doPeerRecovery: Step 3 - peer came back online.");
                        } else {
                            logger.debug("doPeerRecovery: Step 3 - peer still offline. Last try.");
                            CasambiSimpleDriverSystem.sendSshCommand();
                            Thread.sleep(2 * min);
                            if (bridgeOnline) {
                                logger.info("doPeerRecovery: Step 4 - peer came back online. Just in time.");
                            } else {
                                logger.warn("doPeerRecovery: Step 4 - last try unsuccessful. Giving up.");
                            }
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("doPeerRecovery: exception {}. Exiting.", e.getMessage());
            }
            peerRecoveryJobRunning = false;
        }
    };
}

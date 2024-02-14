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

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
import org.openhab.binding.casambisimple.internal.driver.messages.CasambiSimpleMessageNetworkState;
import org.openhab.binding.casambisimple.internal.driver.messages.CasambiSimpleMessageScene;
import org.openhab.binding.casambisimple.internal.driver.messages.CasambiSimpleMessageUnit;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.thing.binding.BridgeHandler;
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
 * @version V0.6 230930@hpo Reworked task scheduling (driver start/stop now works properly)
 *
 */
@NonNullByDefault
public class CasambiSimpleBridgeHandler extends BaseBridgeHandler {

    private final Logger logger = LoggerFactory.getLogger(CasambiSimpleBridgeHandler.class);

    private CasambiSimpleBridgeConfiguration config;

    private final HttpClient httpClient;
    public @Nullable CasambiSimpleDriverRest casambiRest;

    private final WebSocketClient webSocketClient;
    public @Nullable CasambiSimpleDriverSocket casambiSocket;

    private @Nullable CasambiSimpleDiscoveryService casambiDiscover;

    // FIXME: this is new, make sure it complies with development guidelines
    public @Nullable ExecutorService casambiExecutor;

    private @Nullable Future<?> pollMessageJob;
    private volatile boolean pollMessageJobRunning = false;

    private @Nullable Future<?> pollUnitStatusJob;
    private volatile boolean pollUnitStatusJobRunning = false;

    private @Nullable Future<?> peerRecoveryJob;
    private volatile boolean peerRecoveryJobRunning = false;

    private @Nullable Future<?> socketKeepAliveJob;
    private volatile boolean socketKeepAliveJobRunning = false;
    private volatile int missedPong = 0;

    private @Nullable Future<?> initSessionJob;

    private volatile boolean bridgeOnline = false;
    private boolean shutdownInProgress = false;

    private final int mSec = 1000;
    private final int min = 60 * mSec;

    public final CasambiSimpleThingsById thingsById = new CasambiSimpleThingsById();

    // --- Constructor ---------------------------------------------------------------------------------------------

    /**
     * CasambiSimpleBridgeHandler constructor instantiates the class for communication with the Casambi cloud service
     *
     * @param bridge - bridge as a thing
     * @param webSocketClient - from the webSocketFactory (OpenHAB infrastructure)
     * @param httpClient2 - from the httpClientFactory (OpenHAB infrastructure)
     *
     *            Just a couple of variable assignments. The actual initialization is done by the initialize() method
     */
    public CasambiSimpleBridgeHandler(Bridge bridge, WebSocketClient webSocketClient, HttpClient httpClient) {
        super(bridge);
        logger.debug("CasambiSimpleBridgeHandler: #{} constructor with webSocketClient #{}, httpClient #{}",
                this.hashCode(), webSocketClient.hashCode(), httpClient.hashCode());
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
     * initialize gets bridge initialization going. The actual work is done asynchronously with initCasambiSession.
     * Additionally, the discovery service is set up
     */
    @Override
    public void initialize() {
        logger.debug("initialize: setting up bridge #{}", this.hashCode());

        // Setup REST & WebSocket interface and start jobs
        if (initSessionJob != null) {
            logger.debug("casambiBridge:initialize stopping initSessionJob #{}", initSessionJob.hashCode());
            initSessionJob.cancel(true);
        }
        // initSessionJobRunning = false;
        // initSessionJob = scheduler.submit(initCasambiSession);
        casambiExecutor = Executors.newFixedThreadPool(10);
        initSessionJob = casambiExecutor.submit(initCasambiSession);
        logger.debug("initialize: initSessionJob #{} started", initCasambiSession.hashCode());

        // Discovery Handler
        BridgeHandler bridgeHandler = this.getThing().getHandler();
        final CasambiSimpleDiscoveryService localCasambiDiscover = casambiDiscover;
        if (bridgeHandler != null && localCasambiDiscover != null) {
            localCasambiDiscover.setThingHandler(bridgeHandler);
            logger.debug("initialize: bridgeHandler set up");
        }
    }

    /**
     * dispose tears down the bridge including stopping jobs and closing socket etc.
     *
     */
    @Override
    public void dispose() {
        logger.info("casambiBridge:dispose tear down bridge #{}", this.hashCode());
        shutdownInProgress = true;

        if (casambiExecutor != null) {
            List<Runnable> stoppedJobs = casambiExecutor.shutdownNow();
            try {
                casambiExecutor.awaitTermination(5 * mSec, TimeUnit.MILLISECONDS); // Wait 5 seconds
                logger.debug("casambiBridge:dispose all Jobs shut down.");
            } catch (InterruptedException e) {
                logger.warn("casambiBridge:dispose Exception shutting down casambiExecutor {}", e.getMessage());
            }
        } else {
            logger.warn("casambiBridge:dispose casambiExecutor is null");
        }

        // FIXME: these should all have been shut down by now
        if (pollMessageJob != null) {
            int hc = pollMessageJob.hashCode();
            pollMessageJob.cancel(true);
            pollMessageJobRunning = false;
            logger.debug("casambiBridge:dispose pollMessageJob #{} stopped", hc);
        }
        if (socketKeepAliveJob != null) {
            int hc = socketKeepAliveJob.hashCode();
            socketKeepAliveJob.cancel(true);
            socketKeepAliveJobRunning = false;
            logger.debug("casambiBridge:dispose keepAlive #{} stopped", hc);
        }
        if (pollUnitStatusJob != null) {
            int hc = pollUnitStatusJob.hashCode();
            pollUnitStatusJob.cancel(true);
            pollUnitStatusJobRunning = false;
            logger.debug("casambiBridge:dispose pollStatus #{} stopped", hc);
        }
        if (peerRecoveryJob != null) {
            int hc = peerRecoveryJob.hashCode();
            peerRecoveryJob.cancel(true);
            peerRecoveryJobRunning = false;
            logger.debug("casambiBridge:dispose peerRecovery #{} stopped", hc);
        }

        // Close sockets before opening new ones
        if (casambiSocket != null) {
            logger.debug("casambiBridge:dispose closing socket #{}", casambiSocket.hashCode());
            casambiSocket.close();
            casambiSocket = null;
        }
        // FIXME: wait for socket to be closed before closing REST?
        if (casambiRest != null) {
            logger.debug("casambiBridge:dispose closing rest #{}", casambiRest.hashCode());
            casambiRest.close();
            casambiRest = null;
        }
        bridgeOnline = false;
        super.dispose();
        logger.debug("casambiBridge:dispose done");
    }

    /**
     * handleCommand handles channel commands to the bridge
     *
     * @param channelUID - BRIDGE_CHANNEL_DIM and BRIDGE_CHANNEL_RESTART
     * @param command - PercentType for network level dimming, OnOffType for bridge restart
     */
    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        logger.debug("handleCommand: (Bridge) channel uid {}, command {}", channelUID, command);
        CasambiSimpleDriverSocket casambiSocketLocal = casambiSocket;

        if (casambiSocketLocal != null) {
            if (command instanceof RefreshType) {
                // logger.trace("handleCommand: (Bridge) doRefresh NOP");
            } else if (BRIDGE_CHANNEL_DIM.equals(channelUID.getId())) {
                // Set network dim level (0-100)
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

    // Optional method, for development only, not production
    // @Override
    // public void childHandlerInitialized(ThingHandler handler, Thing thing) {
    // logger.info("childHandlerInitialized: Thing {}, NOP!", thing);
    // Das ist Quatsch hier - nur implementieren, wenn auch etwas sinnvolles passiert (mit dem thing!)
    // updateStatus(ThingStatus.ONLINE);
    // }

    // Optional method, for development only, not production
    // @Override
    // public void childHandlerDisposed(ThingHandler handler, Thing thing) {
    // logger.info("childHandlerDispose: Thing {}, NOP!", thing);
    // Das ist Quatsch hier - nur implementieren, wenn auch etwas sinnvolles passiert (mit dem thing!)
    // updateStatus(ThingStatus.REMOVED);
    // }

    // --- More instance methods --------------------------------------------------------------------------------

    public Boolean getBridgeStatus() {
        return bridgeOnline;
    }

    public void registerDiscoveryListener(CasambiSimpleDiscoveryService discoveryHandler) {
        logger.debug("registerDiscoveryListener:");
        casambiDiscover = discoveryHandler;
    }

    public void unregisterDiscoveryListener() {
        logger.debug("unregisterDiscoveryListener:");
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
     * FIXME: what to do, if initialisation is not successful? repeat? give up?
     */
    private Runnable initCasambiSession = new Runnable() {
        @Override
        public void run() {
            logger.debug("initCasambiSession: starting Job #{}", initCasambiSession.hashCode());

            // FIXME: these should be all null if cleanup works properly,
            if (pollUnitStatusJob != null) {
                logger.debug("initCasambiSession: cancelling pollUnitStatusJob #{}", pollUnitStatusJob.hashCode());
                pollUnitStatusJob.cancel(true);
            }
            pollUnitStatusJobRunning = false;

            if (pollMessageJob != null) {
                logger.debug("initCasambiSession: cancelling pollMessageJob #{}", pollMessageJob.hashCode());
                pollMessageJob.cancel(true);
            }
            pollMessageJobRunning = false;

            if (socketKeepAliveJob != null) {
                logger.debug("initCasambiSession: cancelling socketKeepAliveJob #{}", socketKeepAliveJob.hashCode());
                socketKeepAliveJob.cancel(true);
            }
            socketKeepAliveJobRunning = false;

            if (peerRecoveryJob != null) {
                logger.debug("initCasambiSession: cancelling peerRecoveryJob #{}", peerRecoveryJob.hashCode());
                peerRecoveryJob.cancel(true);
            }
            peerRecoveryJobRunning = false;

            bridgeOnline = false;
            try {

                // Logger - starting
                CasambiSimpleDriverLogger messageLogger = new CasambiSimpleDriverLogger(config.logMessages,
                        config.logDir, "casambiJsonMessages.txt");
                messageLogger.dumpMessage("+++ initCasambiSession - logger started +++");

                // REST Session - starting
                casambiRest = new CasambiSimpleDriverRest(config.apiKey, config.userId, config.userPassword,
                        config.networkPassword, messageLogger, webSocketClient, httpClient);
                logger.debug("initCasambiSession: opened REST session #{}", casambiRest.hashCode());
                CasambiSimpleDriverSystem.configureRestartCommand(config.useRemCmd, config.remCmdStr);
                final CasambiSimpleDriverRest casambiRestLocal = casambiRest;
                if (casambiRestLocal != null) {

                    // Sessions userSession and networkSession - starting
                    // FIXME: unused variables
                    // CasambiSimpleMessageSession userSession;
                    // Map<String, CasambiSimpleMessageNetwork> networkSession;
                    if ((/* userSession = */ casambiRestLocal.createUserSession()) == null) {
                        throw new CasambiSimpleException("User session could not be initialized");
                    } else if (( /* networkSession = */ casambiRestLocal.createNetworkSession()) == null) {
                        throw new CasambiSimpleException("Network session could not be initialized");
                    } else {

                        // Job pollUnitStatus - starting
                        if (!pollUnitStatusJobRunning) {
                            // pollUnitStatusJob = scheduler.submit(pollUnitStatus);
                            pollUnitStatusJob = casambiExecutor.submit(pollUnitStatus);
                            logger.debug("initCasambiSession: pollUnitStatusJob #{} started",
                                    pollUnitStatusJob.hashCode());
                        } else {
                            logger.debug("initCasambiSession: pollUnitStatusJob already running");
                        }

                        // Socket - opening
                        casambiSocket = casambiRestLocal.getNewCasambiSocket();
                        logger.debug("initCasambiSession: opened socket #{}", casambiSocket.hashCode());
                        if (/* casambiSocket != null && */ casambiSocket.open()) {
                            bridgeOnline = true;
                            updateStatus(ThingStatus.ONLINE);
                            updateState(BRIDGE_CHANNEL_PEER, OnOffType.ON);

                            // Job pollMessageJob - starting
                            if (!pollMessageJobRunning) {
                                // pollMessageJob = scheduler.submit(handleCasambiMessages);
                                pollMessageJob = casambiExecutor.submit(handleCasambiMessages);
                                logger.debug("initCasambiSession: pollMessageJob #{} started",
                                        pollMessageJob.hashCode());
                            } else {
                                logger.debug("initCasambiSession: pollMessageJob already running");
                            }

                            // Job socketKeepAliveJob - starting
                            if (!socketKeepAliveJobRunning) {
                                // socketKeepAliveJob = scheduler.submit(socketKeepAlive);
                                socketKeepAliveJob = casambiExecutor.submit(socketKeepAlive);
                                logger.debug("initCasambiSession: socketKeepAliveJob #{} started",
                                        socketKeepAliveJob.hashCode());
                            } else {
                                logger.debug("initCasambiSession: socketKeepAliveJob already running");
                            }
                            logger.debug("initCasambiSession: session initialized");

                        } else {
                            // FIXME: how do we handle socket open errors here?
                            logger.error("initCasambiSession: Socket not open");
                            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                                    "Error: Socket not open.");
                        }
                    }
                } else {
                    // FIXME: how do we handle rest open errors here?
                    logger.error("initCasambiSession: #{} REST session not open", initCasambiSession.hashCode());
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                            "Error: REST session not open.");
                }
            } catch (Exception e) {
                logger.warn("initCasambiSession: #{} Exception {}", initCasambiSession.hashCode(), e.getMessage());
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        "Exception during session initialisation: " + e.getMessage());
            }
            logger.debug("initCasambiSession: #{} done.", initCasambiSession.hashCode());
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
            logger.debug("handleCasambiMessages: starting runnable #{}", handleCasambiMessages.hashCode());
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

                                    Thing thing = thingsById.getFirstLuminaire(msg.id);
                                    if (thing != null) {
                                        CasambiSimpleLuminaireHandler thingHandler = (CasambiSimpleLuminaireHandler) thing
                                                .getHandler();
                                        if (thingHandler != null) {
                                            // Update online status
                                            if (msg.online != null && msg.online) {
                                                thingHandler.updateLuminaireStatus(ThingStatus.ONLINE);
                                            } else {
                                                logger.info("handleCasambiMessages: status OFFLINE, id {}", msg.id);
                                                thingHandler.updateLuminaireStatus(ThingStatus.OFFLINE);
                                            }
                                            org.openhab.core.thing.Channel channel = thing
                                                    .getChannel(LUMINAIRE_CHANNEL_DIMMER);
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
                                        if (config.useRemCmd) {
                                            // peerRecoveryJob = scheduler.submit(doPeerRecovery);
                                            peerRecoveryJob = casambiExecutor.submit(doPeerRecovery);
                                            logger.debug("initCasambiSession: peerRecoveryJob #{} started",
                                                    peerRecoveryJob.hashCode());
                                        }
                                    } else {
                                        logger.debug(
                                                "handleCasambiMessages: bridge still offline during recovery. Let's see.");
                                    }
                                }
                                break;
                            case networkUpdated:
                                logger.info("handleCasambiMessages: networkUpdated online {}", msg.online);
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
                            case networkLog:
                                logger.debug("handleCasambiMessages: networkLog: {}", msg.message);
                                // FIXME: What to do?
                                break;
                            case keepAlive:
                                logger.debug("handleCasambiMessages: keepAlive got pong");
                                missedPong = 0;
                                break;
                            default:
                                logger.info("handleCasambiMessages: unknown message type: {}", msg);
                        }
                    } else {
                        logger.info("handleCasambiMessages: got null message.");
                        if (casambiSocket != null) {
                            logger.debug("handleCasambiMessages: null message socketStatus {}",
                                    casambiSocket.getSocketStatus());
                        }
                    }
                } else {
                    logger.warn("handleCasambiMessages: #{} casambi socket or rest driver is null. Continuing.",
                            handleCasambiMessages.hashCode());
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
     * Currently only the state of luminaires is updated (by calling the appropriate thing handler).
     *
     * Scene and group status does not get updated. It is not quite clear, how the scene and group status can be defined
     * in a meaningful way.
     */
    private Runnable pollUnitStatus = new Runnable() {
        @Override
        public void run() {
            logger.debug("pollUnitStatus: starting runnable #{}", pollUnitStatus.hashCode());
            pollUnitStatusJobRunning = true;

            while (true) {
                if (Thread.interrupted()) {
                    logger.info("pollUnitStatus: got thread interrupt. Exiting job.");
                    pollUnitStatusJobRunning = false;
                    return;
                }
                logger.debug("pollUnitStatus: #{}", this.hashCode());
                boolean allOffline = true;
                try {
                    // Poll the unit status every 10 Minutes
                    Thread.sleep(600 * mSec);
                    if (casambiRest != null) {
                        CasambiSimpleMessageNetworkState networkState = casambiRest.getNetworkState();
                        if (networkState != null) {

                            // Get status of luminaires
                            if (networkState.units != null) {

                                for (Entry<Integer, CasambiSimpleMessageUnit> unit : networkState.units.entrySet()) {
                                    Thing thing = thingsById.getFirstLuminaire(unit.getKey());
                                    if (thing != null) {
                                        CasambiSimpleLuminaireHandler thingHandler = (CasambiSimpleLuminaireHandler) thing
                                                .getHandler();
                                        if (thingHandler != null) {
                                            CasambiSimpleMessageUnit unitState = unit.getValue();
                                            if (unitState.online == true) {
                                                allOffline = false;
                                            }
                                            thingHandler.updateLuminaireState(unitState);
                                        }
                                    } else {
                                        logger.info("pollUnitStatus: got status for unknown id {}, name {}",
                                                unit.getKey(), unit.getValue().name);
                                    }
                                }

                            } else {
                                logger.trace("pollUnitStatus: no units in network.");
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
                                logger.trace("pollUnitStatus: no scenes in network.");
                            }

                        } else {
                            logger.info("pollUnitStatus: got null network state message.");
                        }
                    } else {
                        logger.warn("pollUnitStatus: #{} CasambiDriverRest is null. Stopping Job.",
                                pollUnitStatus.hashCode());
                        pollUnitStatusJobRunning = false;
                        return;
                    }
                } catch (InterruptedException ie) {
                    logger.warn("pollUnitStatus: #{} exception {}. Exiting.", pollUnitStatus.hashCode(),
                            ie.getMessage());
                    Thread.currentThread().interrupt();
                } catch (Exception oe) {
                    logger.warn("pollUnitStatus: #{} exception {}. Continuing.", pollUnitStatus.hashCode(),
                            oe.getMessage());
                }
                if (allOffline) {
                    // cycles bluetooth on the Android device
                    if (!shutdownInProgress) {
                        logger.info("pollUnitStatus: all devices offline, restarting Bluetooth");
                        CasambiSimpleDriverSystem.sendSshBluetoothCommand();
                    }
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
            logger.debug("socketKeepAlive: starting runnable #{}", socketKeepAlive.hashCode());
            socketKeepAliveJobRunning = true;
            while (true) {
                if (Thread.interrupted()) {
                    logger.info("socketKeepAlive: got thread interrupt. Exiting job.");
                    socketKeepAliveJobRunning = false;
                    return;
                }
                try {
                    // Sleep for slightly less than 5 minutes
                    Thread.sleep(280 * mSec);
                    if (missedPong > 0) {
                        logger.trace("socketKeepAlive: Response {} missing for ping.", missedPong);
                        // FIXME: will this help?
                        if (missedPong > 10) {
                            if (config.useRemCmd && casambiSocket != null) {
                                logger.info("socketKeepAlive: {} ping responses missing. Sending recovery command.",
                                        missedPong);
                                CasambiSimpleDriverSystem.sendSshRestartCommand();
                                missedPong = 0;
                            }
                        }
                    }
                    if (casambiSocket != null) {
                        logger.trace("socketKeepAlive: #{} ping", socketKeepAlive.hashCode());
                        casambiSocket.ping();
                        missedPong++;
                    } else {
                        logger.info("socketKeepAlive: #{}, socket is null. Exiting.", socketKeepAlive.hashCode());
                        return;
                    }
                } catch (InterruptedException ie) {
                    logger.warn("socketKeepAlive: #{} exception {}. Exiting.", socketKeepAlive.hashCode(),
                            ie.getMessage());
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    logger.warn("socketKeepAlive: #{} exception {}. Continuing.", socketKeepAlive.hashCode(),
                            e.getMessage());
                    // FIXME: reopen socket here?
                    if (casambiSocket != null & !shutdownInProgress) {
                        logger.debug("socketKeepAlive: reopening socket #{}.", casambiSocket.hashCode());
                        casambiSocket.reopen();
                    }
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
            peerRecoveryJobRunning = true;
            logger.info("doPeerRecovery: starting runnable #{}", doPeerRecovery.hashCode());
            try {
                Thread.sleep(2 * min);
                if (bridgeOnline) {
                    logger.debug("doPeerRecovery: Step 1 - peer came back online. Recovery ended.");
                } else {
                    logger.debug("doPeerRecovery: Step 1 - peer offline. Sending command.");
                    CasambiSimpleDriverSystem.sendSshRestartCommand();
                    Thread.sleep(3 * min);
                    if (bridgeOnline) {
                        logger.debug("doPeerRecovery: Step 2 - peer came back online. Recovery ended.");
                    } else {
                        logger.debug("doPeerRecovery: Step 2 - peer still offline. Sending command.");
                        CasambiSimpleDriverSystem.sendSshRestartCommand();
                        Thread.sleep(6 * min);
                        if (bridgeOnline) {
                            logger.debug("doPeerRecovery: Step 3 - peer came back online.");
                        } else {
                            logger.debug("doPeerRecovery: Step 3 - peer still offline. Last try.");
                            CasambiSimpleDriverSystem.sendSshRestartCommand();
                            Thread.sleep(2 * min);
                            if (bridgeOnline) {
                                logger.debug("doPeerRecovery: Step 4 - peer came back online. Just in time.");
                            } else {
                                logger.info("doPeerRecovery: Step 4 - last try unsuccessful. Giving up.");
                            }
                        }
                    }
                }
            } catch (Exception e) {
                logger.warn("doPeerRecovery: #{} exception {}. Exiting.", doPeerRecovery.hashCode(), e.getMessage());
            }
            peerRecoveryJobRunning = false;
        }
    };
}

// End

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

import java.util.Arrays;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Future;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.casambitest.internal.driver.CasambiDriverJson;
import org.openhab.binding.casambitest.internal.driver.messages.CasambiMessageEvent;
import org.openhab.binding.casambitest.internal.driver.messages.CasambiMessageNetwork;
import org.openhab.binding.casambitest.internal.driver.messages.CasambiMessageNetworkState;
import org.openhab.binding.casambitest.internal.driver.messages.CasambiMessageScene;
import org.openhab.binding.casambitest.internal.driver.messages.CasambiMessageSession;
import org.openhab.binding.casambitest.internal.driver.messages.CasambiMessageUnit;
import org.openhab.core.library.types.OnOffType;
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

    private @Nullable CasambiTestConfiguration config;
    private @Nullable CasambiMessageSession userSession;
    private @Nullable Map<String, CasambiMessageNetwork> networkSession;

    private @Nullable Future<?> pollMessageJob;
    private @Nullable Future<?> sendKeepAliveJob;
    private @Nullable Future<?> pollUnitStatusJob;

    // Read casambi messages from the ring buffer and update channel and thing status accordingly
    // private Runnable pollMessage = doPollMessage;

    public CasambiBridgeHandler(Bridge bridge) {
        super(bridge);
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
        try {
            casambi.casambiSocketClose();
        } catch (Exception e) {
            logger.error("dispose: Exception {}", e.toString());
        }
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

    private Runnable doPollMessage = new Runnable() {
        @Override
        public void run() {
            while (true) {
                if (casambi != null) {
                    CasambiMessageEvent msg = casambi.receiveMessage();
                    if (msg != null) {
                        switch (msg.getMessageType()) {
                            case unitChanged:
                                // Boolean m2 = Optional.ofNullable(msg.on).orElse(false);
                                // Integer m1 = Optional.ofNullable(msg).ofNullable(msg.id).orElse(0);
                                // Float m3 = Optional.ofNullable(msg.dimLevel).orElse((float) 0.0);
                                if (msg.id != null && msg.on != null) {
                                    logger.debug("pollCasambiMessages: unitChanged id {}, online {}, on {}, dim {}",
                                            msg.id, msg.online, msg.on, msg.dimLevel);
                                    // Update online status
                                    if (msg.online != null && msg.online) {
                                        logger.warn("pollCasambiMessages: status OFFLINE, id {}", msg.id);
                                        updateStatus(ThingStatus.ONLINE);
                                    } else {
                                        updateStatus(ThingStatus.OFFLINE);
                                    }
                                    Thing thing = CasambiLuminaryHandler.getThingById(msg.id);
                                    // Update luminary state
                                    // Optional<ThingHandler> m1 = Optional.ofNullable(thing.getHandler());
                                    // m1.ifPresent((a, b) -> thing.getHandler().updateState(a, b));
                                    if (thing != null) {
                                        CasambiLuminaryHandler thingHandler = (CasambiLuminaryHandler) thing
                                                .getHandler();
                                        if (thingHandler != null) {
                                            thingHandler.updateState(thing.getChannel(CHANNEL_SWITCH).getUID(),
                                                    msg.on ? OnOffType.ON : OnOffType.OFF);
                                        }
                                    }
                                }
                                break;
                            case peerChanged:
                                logger.debug("pollCasambiMessages: peerChanged online {}", msg.online);
                                updateStatus(msg.online ? ThingStatus.ONLINE : ThingStatus.OFFLINE);
                                break;
                            case networkUpdated:
                                logger.warn("pollCasambiMessages: networkUpdated online {}", msg.online);
                                break;
                            case socketChanged: // Driver message
                                logger.warn("pollCasambiMessages: socketChanged, status {}, message {}", msg.status,
                                        msg.response);
                                break;
                            case wireStatusOk:
                                logger.debug("pollCasambiMessages: wireStatus: {}", msg.wireStatus);
                                break;
                            case wireStatusError:
                                logger.warn("pollCasambiMessages: wireStatus error: {}", msg.wireStatus);
                                break;
                            case keepAlive:
                                logger.debug("pollCasambiMessages: keepAlive got pong");
                                casambi.pingOk();
                                break;
                            default:
                                logger.warn("pollCasambiMessages: unknown message type: {}", msg);
                        }
                    } else {
                        logger.warn("pollCasambiMessages: got null message.");
                        logger.debug("pollCasambiMessages: socketStatus {}", casambi.getSocketStatus());
                    }
                } else {
                    logger.info("+++ pollCasambiMessages: casambi driver is null.");
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Casambi driver is null");
                }
            }
        };
    };

    // Initiate casambi session.
    private Runnable initCasambiSession = new Runnable() {
        // @SuppressWarnings("null")
        @Override
        public void run() {
            logger.debug("initCasambiSession: initializing session with casambi site");
            try {
                config = getConfigAs(CasambiTestConfiguration.class);
                casambi = new CasambiDriverJson(config.apiKey, config.userId, config.userPassword,
                        config.networkPassword);
                // Open session with casambi server
                userSession = casambi.createUserSession();
                networkSession = casambi.createNetworkSession();
                // Open web socket
                casambi.casambiSocketOpen();
            } catch (Exception e) {
                logger.error("initCasambiSession: Exception {}", e.toString());
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        "Exception during session initialisation: " + e.toString());
            }
        };
    };

    // Send a ping message on the web socket to keep it open
    private Runnable sendKeepAlive = new Runnable() {
        @Override
        public void run() {
            while (true) {
                try {
                    Thread.sleep(280 * 1000);
                    logger.debug("sendKeepAlive:");
                    if (casambi != null) {
                        casambi.ping();
                    }
                } catch (Exception e) {
                    logger.error("sendKeepAlive: exception {}", e);
                }
            }
        }
    };

    // Do regular polls of the network state and adjust bridge and things accordingly
    private Runnable pollUnitStatus = new Runnable() {
        @Override
        public void run() {
            while (true) {
                try {
                    Thread.sleep(600 * 1000);
                    logger.debug("pollUnitStatus:");
                    CasambiMessageNetworkState networkState = casambi.getNetworkState();
                    if (networkState != null) {
                        if (networkState.units != null) {
                            for (Entry<Integer, CasambiMessageUnit> unit : networkState.units.entrySet()) {
                                Thing thing = CasambiLuminaryHandler.getThingById(unit.getKey());
                                if (thing != null) {
                                    CasambiLuminaryHandler thingHandler = (CasambiLuminaryHandler) thing.getHandler();
                                    CasambiMessageUnit unitState = unit.getValue();
                                    if (thingHandler != null) {
                                        thingHandler.updateLuminaryState(unitState);
                                    }
                                } else {
                                    logger.warn("pollUnitStatus: got status for unknown id {}, name {}", unit.getKey(),
                                            unit.getValue().name);
                                }
                                // Optional.ofNullable(networkState);
                                // Optional<Map<Integer, CasambiMessageUnit>> e =
                                // Optional.ofNullable(networkState.units);
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
                        logger.warn("pollUnitStatus: got null message.");
                    }
                } catch (Exception e) {
                    logger.error("pollUnitStatus: exception {}", e);
                }
            }
        };
    };
}

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

import static org.openhab.binding.casambitest.internal.CasambiTestBindingConstants.CHANNEL_SWITCH;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Future;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.casambitest.internal.driver.CasambiDriverJson;
import org.openhab.binding.casambitest.internal.driver.messages.CasambiMessageEvent;
import org.openhab.binding.casambitest.internal.driver.messages.CasambiMessageNetwork;
import org.openhab.binding.casambitest.internal.driver.messages.CasambiMessageSession;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.thing.Bridge;
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
    private @Nullable Map<Integer, Thing> thingsById = new HashMap<Integer, Thing>();
    private @Nullable Map<Integer, Thing> scenesById = new HashMap<Integer, Thing>();
    private @Nullable Future<?> pollingJob = null;
    private @Nullable Future<?> keepAliveJob;

    public CasambiBridgeHandler(Bridge bridge) {
        super(bridge);
        // logger.debug("constructor: bridge");
    };

    // Initiate casambi session.
    private Runnable initCasambiSession = () -> {
        logger.debug("initCasambiSession: initializing session with casambi site");
        try {
            casambi = new CasambiDriverJson(config.apiKey, config.userId, config.userPassword, config.networkPassword);
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

    // Read casambi messages from the ring buffer and update channel and thing status accordingly
    private Runnable pollCasambiMessages = () -> {
        while (true) {
            @Nullable
            CasambiMessageEvent msg;
            // logger.debug("pollCasambiMessages: polling messages");
            if (casambi != null) {
                if ((msg = casambi.receiveMessage()) != null) {
                    if (msg.method != null) {
                        logger.info("pollCasambiMessages: method: {}, online: {}", msg.method, msg.online);
                        switch (msg.method) {
                            case "unitChanged":
                                logger.info("pollCasambiMessages: unitChanged id {}, on {}", msg.id, msg.on);
                                if (msg.id != null && msg.on != null) {
                                    Thing thing = getThingById(msg.id);
                                    ((CasambiLuminaryHandler) thing.getHandler()).updateState(
                                            thing.getChannel(CHANNEL_SWITCH).getUID(),
                                            msg.on ? OnOffType.ON : OnOffType.OFF);
                                }
                                break;
                            case "peerChanged":
                                logger.info("pollCasambiMessages:  peerChanged online {}", msg.online);
                                logger.debug("pollCasambiMessages: socketStatus {}", casambi.getSocketStatus());
                                updateStatus(msg.online ? ThingStatus.ONLINE : ThingStatus.OFFLINE);
                                break;
                            default:
                                logger.warn("pollCasambiMessages: unexpected message method: {}", msg.method);
                        }
                    } else if (msg.wireStatus != null) {
                        logger.info("pollCasambiMessages: wireStatus: {}", msg.wireStatus);
                        logger.debug("pollCasambiMessages: socketStatus {}", casambi.getSocketStatus());
                        if (msg.wireStatus == "openWireSucceded") {
                            // Change bridge Status (or not?)
                        }
                    } else if (msg.response != null) {
                        logger.debug("pollCasambiMessages: response: {}, socket status {}", msg.response,
                                casambi.getSocketStatus());
                        if (msg.response == "pong") {
                            casambi.pingOk();
                        }
                    } else {
                        logger.warn("pollCasambiMessages: unrecognized message type. {}", msg);
                        logger.debug("pollCasambiMessages: socketStatus {}", casambi.getSocketStatus());
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

    // Send a ping message on the web socket to keep it open
    private Runnable sendKeepAlive = () -> {
        try {
            while (true) {
                logger.debug("sendKeepAlive:");
                if (casambi != null) {
                    casambi.ping();
                }
                Thread.sleep(280 * 1000);
            }
        } catch (Exception e) {
            logger.error("sendKeepAlive: exception {}", e);
        }
    };

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        logger.debug("handleCommand: (Bridge) channel uid {}, command {}", channelUID, command);
        if (command instanceof RefreshType) {
            // TODO: handle data refresh
        }
    }

    @Override
    public void initialize() {
        logger.debug("initialize: setting up bridge");
        updateStatus(ThingStatus.UNKNOWN);
        config = getConfigAs(CasambiTestConfiguration.class);
        scheduler.execute(initCasambiSession);
        pollingJob = scheduler.submit(pollCasambiMessages);
        keepAliveJob = scheduler.submit(sendKeepAlive);
    }

    @Override
    public void dispose() {
        logger.debug("dispose: tear down bridge");
        if (pollingJob != null) {
            pollingJob.cancel(true);
        }
        if (keepAliveJob != null) {
            keepAliveJob.cancel(true);
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
    }

    // Optional method
    @Override
    public void childHandlerDisposed(ThingHandler handler, Thing thing) {
        logger.debug("childHandlerDispose: NOP");
        // Das ist Quatsch hier - nur implementieren, wenn auch etwas sinnvolles passiert (mit dem thing!)
        // updateStatus(ThingStatus.REMOVED);
    }

    // Map Luminary ids to things. Needed to update thing status based on casambi message content
    // Get thing corresponding to id
    public @Nullable Thing getThingById(@Nullable Integer id) {
        logger.debug("getThingById: id {}", id);
        if (id != null) {
            return thingsById.get(id);
        } else {
            return null;
        }
    }

    // Add a (new) thing to the mapping
    public void putThingById(@Nullable Integer id, @Nullable Thing thing) {
        logger.debug("putThingById: id {}", id);
        if (id != null && thing != null) {
            thingsById.putIfAbsent(id, thing);
        }
    }

    // Map Scene ids to things. Needed to update thing status based on casambi message content
    // Get thing corresponding to id
    public @Nullable Thing getSceneById(@Nullable Integer id) {
        logger.debug("getScenesById: id {}", id);
        if (id != null) {
            return scenesById.get(id);
        } else {
            return null;
        }
    }

    // Add a (new) thing to the mapping
    public void putSceneById(@Nullable Integer id, @Nullable Thing thing) {
        logger.debug("putSceneById: id {}", id);
        if (id != null && thing != null) {
            scenesById.putIfAbsent(id, thing);
        }
    }
}

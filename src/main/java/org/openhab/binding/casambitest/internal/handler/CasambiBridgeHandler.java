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

import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.casambitest.internal.driver.CasambiDriverJson;
import org.openhab.binding.casambitest.internal.driver.messages.CasambiMessageEvent;
import org.openhab.binding.casambitest.internal.driver.messages.CasambiMessageNetwork;
import org.openhab.binding.casambitest.internal.driver.messages.CasambiMessageSession;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.binding.BaseThingHandler;
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
 */
@NonNullByDefault
public class CasambiBridgeHandler extends BaseThingHandler {

    private final Logger logger = LoggerFactory.getLogger(CasambiBridgeHandler.class);

    private @Nullable CasambiTestConfiguration config;
    private @Nullable CasambiDriverJson casambi;
    private @Nullable CasambiMessageSession userSession;
    private @Nullable Map<String, CasambiMessageNetwork> networkSession;

    public CasambiBridgeHandler(Thing thing) {
        super(thing);
        logger.debug("constructor: bridge");
    }

    private void initCasambiSession() {
        try {
            casambi = new CasambiDriverJson(config.apiKey, config.userId, config.userPassword, config.networkPassword);
            logger.debug("initCasambiSession: initializing session with casambi site");
            userSession = casambi.createUserSession();
            networkSession = casambi.createNetworkSession();
        } catch (Exception e) {
            // TODO Auto-generated catch block
            logger.error("initCasambiSession: Exception {}", e.toString());
        }
    }

    private @Nullable Runnable pollCasambiMessages() {
        // private void pollCasambiMessages() {
        // Loop over messages
        // identify message type
        // get relevant message info
        // update status appropriately
        CasambiMessageEvent msg;
        logger.debug("pollCasambiMessages: polling messages");
        do {
            msg = casambi.receiveMessage();
            if (msg != null) {
                logger.info("+++ Message id: {}, method: {}, online: {}, name: {}, on: {}, status: {}", msg.id,
                        msg.method, msg.online, msg.name, msg.on, msg.status);
            }
            switch (msg.method) {
                case "":
                    if (msg.wireStatus == "openWireSucceded") {
                        // Change bridge Status (or not? see below)
                    }
                    break;
                case "unitChanged":
                    // Change status of msg.id e.g. to msg.dimLevel or msg.on or msg.activeScene
                    break;
                case "peerChanged":
                    if (msg.online) {
                        // Change bridge status
                        updateStatus(ThingStatus.ONLINE);
                    } else {
                        // Change bridge status
                        updateStatus(ThingStatus.OFFLINE);
                    }
                    break;
                default:
                    // Ignore for the time being
                    ;
            }
        } while (msg != null);
        return null;
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        logger.debug("handleCommand: channel uid {}, command {}", channelUID, command);
        if (command instanceof RefreshType) {
            // TODO: handle data refresh
        }

        // TODO: handle command

        // Note: if communication with thing fails for some reason,
        // indicate that by setting the status with detail information:
        // updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
        // "Could not control device at IP address x.x.x.x");
    }

    @Override
    public void initialize() {
        logger.debug("initialize: settin up bridge");
        config = getConfigAs(CasambiTestConfiguration.class);
        initCasambiSession();
        ScheduledFuture<?> pollingJob = scheduler.scheduleWithFixedDelay(this.pollCasambiMessages(), 0, 60,
                TimeUnit.SECONDS);

        // TODO: Initialize the handler.
        // The framework requires you to return from this method quickly. Also, before leaving this method a thing
        // status from one of ONLINE, OFFLINE or UNKNOWN must be set. This might already be the real thing status in
        // case you can decide it directly.
        // In case you can not decide the thing status directly (e.g. for long running connection handshake using WAN
        // access or similar) you should set status UNKNOWN here and then decide the real status asynchronously in the
        // background.

        // set the thing status to UNKNOWN temporarily and let the background task decide for the real status.
        // the framework is then able to reuse the resources from the thing handler initialization.
        // we set this upfront to reliably check status updates in unit tests.
        updateStatus(ThingStatus.UNKNOWN);

        // Example for background initialization:
        scheduler.execute(() -> {
            logger.debug("initialize:scheduler: executing check");
            boolean thingReachable = true; // <background task with long running initialization here>
            // when done do:
            if (thingReachable) {
                updateStatus(ThingStatus.ONLINE);
            } else {
                updateStatus(ThingStatus.OFFLINE);
            }
        });

        // These logging types should be primarily used by bindings
        // logger.trace("Example trace message");
        // logger.debug("Example debug message");
        // logger.warn("Example warn message");

        // Note: When initialization can NOT be done set the status with more details for further
        // analysis. See also class ThingStatusDetail for all available status details.
        // Add a description to give user information to understand why thing does not work as expected. E.g.
        // updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
        // "Can not access device as username and/or password are invalid");
    }
}

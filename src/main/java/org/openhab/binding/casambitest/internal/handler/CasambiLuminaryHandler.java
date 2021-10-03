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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link CasambiLuminaryHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Hein Osenberg - Initial contribution
 */
@NonNullByDefault
public class CasambiLuminaryHandler extends BaseThingHandler {

    private final Logger logger = LoggerFactory.getLogger(CasambiLuminaryHandler.class);

    private @Nullable CasambiTestConfiguration config;

    public CasambiLuminaryHandler(Thing thing) {
        super(thing);
        logger.debug("constructor: luminary");
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        logger.debug("handleCommand: channel uid {}, command {}", channelUID, command);
        if (CHANNEL_SWITCH.equals(channelUID.getId())) {
            if (command instanceof RefreshType) {
                // TODO: handle data refresh
            }

            // TODO: handle command

            // Note: if communication with thing fails for some reason,
            // indicate that by setting the status with detail information:
            // updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
            // "Could not control device at IP address x.x.x.x");
        }
        if (CHANNEL_DIM.equals(channelUID.getId())) {
            if (command instanceof RefreshType) {
                // TODO: handle data refresh
            }

            // TODO: handle command

            // Note: if communication with thing fails for some reason,
            // indicate that by setting the status with detail information:
            // updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
            // "Could not control device at IP address x.x.x.x");
        }
    }

    @Override
    public void initialize() {
        logger.debug("initialize: setting up luninary");
        config = getConfigAs(CasambiTestConfiguration.class);
        updateStatus(ThingStatus.UNKNOWN);
    }
}

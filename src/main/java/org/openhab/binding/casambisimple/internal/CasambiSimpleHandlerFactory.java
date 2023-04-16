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
package org.openhab.binding.casambisimple.internal;

import static org.openhab.binding.casambisimple.internal.CasambiSimpleBindingConstants.*;

import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.casambisimple.internal.handler.CasambiSimpleBridgeHandler;
import org.openhab.binding.casambisimple.internal.handler.CasambiSimpleGroupHandler;
import org.openhab.binding.casambisimple.internal.handler.CasambiSimpleLuminaireHandler;
import org.openhab.binding.casambisimple.internal.handler.CasambiSimpleSceneHandler;
import org.openhab.core.io.net.http.HttpClientFactory;
import org.openhab.core.io.net.http.WebSocketFactory;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.binding.BaseThingHandlerFactory;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerFactory;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link CasambiHandlerFactory} is responsible for creating things and thing
 * handlers.
 *
 * @author Hein Osenberg - Initial contribution
 */
@NonNullByDefault
@Component(configurationPid = "binding.casambisimple", service = ThingHandlerFactory.class)
public class CasambiSimpleHandlerFactory extends BaseThingHandlerFactory {

    private final Logger logger = LoggerFactory.getLogger(CasambiSimpleHandlerFactory.class);

    private static final Set<ThingTypeUID> SUPPORTED_THING_TYPES_UIDS = CasambiSimpleBindingConstants.SUPPORTED_DEVICE_THING_TYPES_UIDS;
    // Do these need to be final? Or do we want to set them to null on deactivation?
    private @Nullable HttpClientFactory httpClientFactory;
    private @Nullable WebSocketFactory webSocketFactory;

    @Activate
    public CasambiSimpleHandlerFactory(@Reference WebSocketFactory webSocketFactory,
            @Reference HttpClientFactory httpClientFactory) {
        this.httpClientFactory = httpClientFactory;
        this.webSocketFactory = webSocketFactory;
    }

    // Check if deactivate helps reduce message count during shutdown
    public void deactivate() {
        logger.debug("handlerFactory:deactivate called.");
        // Do we actually need to do anything here?
        this.httpClientFactory = null;
        this.webSocketFactory = null;
    }

    @Override
    public boolean supportsThingType(ThingTypeUID thingTypeUID) {
        // logger.debug("supportsThingType: check for uid {}", thingTypeUID);
        return SUPPORTED_THING_TYPES_UIDS.contains(thingTypeUID);
    }

    @Override
    protected @Nullable ThingHandler createHandler(Thing thing) {
        final ThingTypeUID thingTypeUID = thing.getThingTypeUID();
        logger.debug("createHandler: uid {}", thingTypeUID);

        if (THING_TYPE_BRIDGE.equals(thingTypeUID)) {
            final Bridge bridge = (Bridge) thing;
            // Using shared clients here - no starting or stopping
            final @Nullable HttpClientFactory htf = httpClientFactory;
            final @Nullable WebSocketFactory wsf = webSocketFactory;
            if (wsf != null && htf != null) {
                return new CasambiSimpleBridgeHandler(bridge, wsf.getCommonWebSocketClient(),
                        htf.getCommonHttpClient());
            } else {
                return null;
            }
        }
        if (THING_TYPE_LUMINAIRE.equals(thingTypeUID)) {
            return new CasambiSimpleLuminaireHandler(thing);
        }
        if (THING_TYPE_SCENE.equals(thingTypeUID)) {
            return new CasambiSimpleSceneHandler(thing);
        }
        if (THING_TYPE_GROUP.equals(thingTypeUID)) {
            return new CasambiSimpleGroupHandler(thing);
        }
        return null;
    }
}

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
package org.openhab.binding.casambisimple.internal;

import static org.openhab.binding.casambisimple.internal.CasambiBindingConstants.*;

import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.casambisimple.internal.handler.CasambiBridgeHandler;
import org.openhab.binding.casambisimple.internal.handler.CasambiGroupHandler;
import org.openhab.binding.casambisimple.internal.handler.CasambiLuminaryHandler;
import org.openhab.binding.casambisimple.internal.handler.CasambiSceneHandler;
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
public class CasambiHandlerFactory extends BaseThingHandlerFactory {

    private final Logger logger = LoggerFactory.getLogger(CasambiHandlerFactory.class);

    // private static final Set<ThingTypeUID> SUPPORTED_THING_TYPES_UIDS = Set.of(THING_TYPE_BRIDGE,
    // THING_TYPE_LUMINARY,
    // THING_TYPE_SCENE);
    private static final Set<ThingTypeUID> SUPPORTED_THING_TYPES_UIDS = CasambiBindingConstants.SUPPORTED_DEVICE_THING_TYPES_UIDS;
    private final HttpClientFactory httpClientFactory;
    private final WebSocketFactory webSocketFactory;

    @Activate
    public CasambiHandlerFactory(@Reference WebSocketFactory webSocketFactory,
            @Reference HttpClientFactory httpClientFactory) {
        this.httpClientFactory = httpClientFactory;
        this.webSocketFactory = webSocketFactory;
    }

    @Override
    public boolean supportsThingType(ThingTypeUID thingTypeUID) {
        // logger.debug("supportsThingType: check for uid {}", thingTypeUID);
        return SUPPORTED_THING_TYPES_UIDS.contains(thingTypeUID);
    }

    @Override
    protected @Nullable ThingHandler createHandler(Thing thing) {
        ThingTypeUID thingTypeUID = thing.getThingTypeUID();
        logger.debug("createHandler: uid {}", thingTypeUID);

        if (THING_TYPE_BRIDGE.equals(thingTypeUID)) {
            Bridge bridge = (Bridge) thing;
            return new CasambiBridgeHandler(bridge, webSocketFactory.getCommonWebSocketClient(),
                    httpClientFactory.getCommonHttpClient());
        }
        if (THING_TYPE_LUMINARY.equals(thingTypeUID)) {
            return new CasambiLuminaryHandler(thing);
        }
        if (THING_TYPE_SCENE.equals(thingTypeUID)) {
            return new CasambiSceneHandler(thing);
        }
        if (THING_TYPE_GROUP.equals(thingTypeUID)) {
            return new CasambiGroupHandler(thing);
        }
        return null;
    }
}

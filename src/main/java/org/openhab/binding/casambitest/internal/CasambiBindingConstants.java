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
package org.openhab.binding.casambitest.internal;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.thing.ThingTypeUID;

/**
 * The {@link CasambiBindingConstants} class defines common constants, which are
 * used across the whole binding.
 *
 * @author Hein Osenberg - Initial contribution
 */
@NonNullByDefault
public class CasambiBindingConstants {

    public static final String BINDING_ID = "casambitest";

    // List of all Thing Type UIDs
    public static final ThingTypeUID BRIDGE_TYPE = new ThingTypeUID(BINDING_ID, "casambibridge");
    public static final ThingTypeUID THING_TYPE_LUMINARY = new ThingTypeUID(BINDING_ID, "luminary");
    public static final ThingTypeUID THING_TYPE_SCENE = new ThingTypeUID(BINDING_ID, "scene");

    public static final Set<ThingTypeUID> SUPPORTED_DEVICE_THING_TYPES_UIDS = new HashSet<>(
            Arrays.asList(BRIDGE_TYPE, THING_TYPE_LUMINARY, THING_TYPE_SCENE));

    // Bridge constants
    // Bridge channels
    public static final String CHANNEL_PEER = "peer";
    public static final String CHANNEL_MESSAGE = "message";

    // Luminary constants
    // Parameters
    public static final String DEVICE_ID = "luminaryNumber";
    public static final String DEVICE_UID = "luminaryUID";
    public static final String DEVICE_NAME = "luminaryName";
    // Channels
    public static final String CHANNEL_SWITCH = "switch";
    public static final String CHANNEL_DIM = "dim";

    // Scene constants
    // Parameters
    public static final String SCENE_ID = "sceneNumber";
    public static final String SCENE_NAME = "sceneName";
    // Channels
    public static final String CHANNEL_SCENE = "scene";
}

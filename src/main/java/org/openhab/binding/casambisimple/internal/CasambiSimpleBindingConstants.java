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
public class CasambiSimpleBindingConstants {

    public static final String BINDING_ID = "casambisimple";

    // List of all Thing Type UIDs
    public static final ThingTypeUID THING_TYPE_BRIDGE = new ThingTypeUID(BINDING_ID, "casambibridge");
    public static final ThingTypeUID THING_TYPE_LUMINAIRE = new ThingTypeUID(BINDING_ID, "luminaire");
    public static final ThingTypeUID THING_TYPE_SCENE = new ThingTypeUID(BINDING_ID, "scene");
    public static final ThingTypeUID THING_TYPE_GROUP = new ThingTypeUID(BINDING_ID, "group");
    // public static final ThingTypeUID THING_TYPE_SWITCH = new ThingTypeUID(BINDING_ID, "switch");

    public static final Set<ThingTypeUID> SUPPORTED_DEVICE_THING_TYPES_UIDS = new HashSet<>(
            Arrays.asList(THING_TYPE_BRIDGE, THING_TYPE_LUMINAIRE, THING_TYPE_SCENE, THING_TYPE_GROUP));

    // Bridge constants
    // Bridge channels
    public static final String BRIDGE_CHANNEL_PEER = "peer";
    public static final String BRIDGE_CHANNEL_MESSAGE = "message";
    public static final String BRIDGE_CHANNEL_DIM = "dim";

    // Luminaire constants
    // Parameters
    public static final String LUMINAIRE_ID = "luminaireId";
    public static final String LUMINAIRE_UID = "luminaireUID";
    public static final String LUMINAIRE_NAME = "luminaireName";

    public static final String LUMINAIRE_HAS_DIMMER = "hasDimmer";
    public static final String LUMINAIRE_HAS_COLOR = "hasColor";
    public static final String LUMINAIRE_HAS_CCT = "hasCCT";
    public static final String LUMINAIRE_TEMPERATURE_MIN = "tempMin";
    public static final String LUMINAIRE_TEMPERATURE_MAX = "tempMax";

    // Channels
    // FIXME: do we need 'onoff' channels?
    public static final String LUMINAIRE_CHANNEL_ONOFF = "onoff";
    public static final String LUMINAIRE_CHANNEL_DIMMER = "dim";
    public static final String LUMINAIRE_CHANNEL_COLOR = "color";
    public static final String LUMINAIRE_CHANNEL_CCT = "cct";

    // Scene constants
    // Parameters
    public static final String SCENE_ID = "sceneId";
    public static final String SCENE_UID = "sceneUID";
    public static final String SCENE_NAME = "sceneName";
    // Channels
    // FIXME: do we need 'onoff' channels?
    public static final String SCENE_CHANNEL_ONOFF = "onoff";
    public static final String SCENE_CHANNEL_DIM = "dim";

    // Group constants
    // Parameters
    public static final String GROUP_ID = "groupId";
    public static final String GROUP_UID = "groupUID";
    public static final String GROUP_NAME = "groupName";
    // Channels
    // FIXME: do we need 'onoff' channels?
    public static final String GROUP_CHANNEL_ONOFF = "onoff";
    public static final String GROUP_CHANNEL_DIM = "dim";
}

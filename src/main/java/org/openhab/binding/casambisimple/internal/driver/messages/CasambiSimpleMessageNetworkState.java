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
package org.openhab.binding.casambisimple.internal.driver.messages;

import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * The {@link CasambiSimpleMessageNetworkState} is used to parse network state messages with gson
 *
 * @author Hein Osenberg - Initial contribution
 * @version V0.1 210827@hpo First version, setup IDE
 */
@NonNullByDefault
public class CasambiSimpleMessageNetworkState {
    public String id;
    public String name;
    public @Nullable Integer revision;
    public @Nullable String grade;
    public String address;
    public @Nullable CasambiSimpleMessageGateway gateway;
    public String type;
    public @Nullable String timezone;
    public @Nullable Float dimLevel;
    public @Nullable Integer @Nullable [] activeScenes;
    public @Nullable CasambiSimpleMessagePhoto @Nullable [] photos;
    public @Nullable Map<Integer, CasambiSimpleMessageUnit> units;
    public @Nullable Map<Integer, CasambiSimpleMessageScene> scenes;
    public @Nullable Map<Integer, CasambiSimpleMessageGroup> groups;

    CasambiSimpleMessageNetworkState() {
        id = "";
        name = "";
        address = "";
        type = "";
        photos = null;
        activeScenes = null;
    }
}

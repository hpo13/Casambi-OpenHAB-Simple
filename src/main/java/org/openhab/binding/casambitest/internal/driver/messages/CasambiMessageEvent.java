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
package org.openhab.binding.casambitest.internal.driver.messages;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * The {@link CasambiMessageEvent} is used to parse event messages with gson
 *
 * @author Hein Osenberg - Initial contribution
 * @version V0.1 210827@hpo First version, setup IDE
 */
@NonNullByDefault
public class CasambiMessageEvent {
    public @Nullable String method;
    public @Nullable Integer priority;
    public @Nullable Integer id;
    public @Nullable Integer groupId;
    public @Nullable Integer position;
    public @Nullable String address;
    public @Nullable String name;
    public @Nullable Integer fixtureId;
    public @Nullable String type;
    public @Nullable Integer condition;
    public @Nullable Integer wire;
    // public Set<String> sensors;
    public @Nullable Boolean online;
    public @Nullable Integer activeSceneId;
    public @Nullable Float dimLevel;
    // public ComplicatedStructure details;
    public @Nullable Boolean on;
    public @Nullable String status;
    public @Nullable CasambiMessageControl @Nullable [] controls;
    public @Nullable String ref;
    public @Nullable String wireStatus;
    public @Nullable String response;

    CasambiMessageEvent() {
        id = 0;
        name = "";
        address = "";
        controls = null;
    };

    public @Nullable CasambiMessageEvent parseJson(JsonObject netInfo) {
        Gson gson = new Gson();
        return gson.fromJson(netInfo, CasambiMessageEvent.class);
    }
}

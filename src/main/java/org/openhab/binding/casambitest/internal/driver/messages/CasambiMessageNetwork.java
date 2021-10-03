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
 * The {@link CasambiMessageNetwork} is used to parse control structures with gson
 *
 * @author Hein Osenberg - Initial contribution
 * @version V0.1 210827@hpo First version, setup IDE
 */
@NonNullByDefault
public class CasambiMessageNetwork {
    public String id;
    public String address;
    public String name;
    public String type;
    public String grade;
    public @Nullable String role;
    public @Nullable String sessionId;
    public @Nullable Integer revision;

    CasambiMessageNetwork() {
        id = "";
        address = "";
        name = "";
        type = "";
        grade = "";
    };

    @Nullable
    public CasambiMessageNetwork parseJson(JsonObject netInfo) {
        Gson gson = new Gson();
        return gson.fromJson(netInfo, CasambiMessageNetwork.class);
    }
}

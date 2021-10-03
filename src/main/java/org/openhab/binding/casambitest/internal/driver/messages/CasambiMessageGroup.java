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
 * The {@link CasambiMessageGroup} is used to parse group structures with gson
 *
 * @author Hein Osenberg - Initial contribution
 * @version V0.1 210827@hpo First version, setup IDE
 */
@NonNullByDefault
public class CasambiMessageGroup {
    public String name;
    public Integer id;
    public @Nullable Integer position;
    // public Map<Integer, CasambiMessageUnit> units;

    CasambiMessageGroup() {
        name = "";
        id = 0;
    };

    public @Nullable CasambiMessageGroup parseJson(JsonObject photoInfo) {
        Gson gson = new Gson();
        return gson.fromJson(photoInfo, CasambiMessageGroup.class);
    }
}

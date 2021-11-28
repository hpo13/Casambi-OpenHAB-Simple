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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * The {@link CasambiMessageUnit} is used to parse single unit structures with gson
 *
 * @author Hein Osenberg - Initial contribution
 * @version V0.1 210827@hpo First version, setup IDE
 */
@NonNullByDefault
public class CasambiMessageUnit {
    public Integer id;
    public String address;
    public String name;
    public @Nullable Integer position;
    public Integer fixtureId;
    public @Nullable String firmwareVersion;
    public @Nullable Integer groupId;
    public @Nullable Integer priority;
    public @Nullable Integer scene;
    public @Nullable Boolean on;
    public @Nullable Boolean online;
    public @Nullable Integer conditon;
    public @Nullable String status;
    public @Nullable Integer activeSceneId;
    public @Nullable CasambiMessageControl @Nullable [][] controls;
    // public Detail details;
    public @Nullable Float dimLevel;
    // public Set<String> labels;
    public String type;

    CasambiMessageUnit() {
        id = -1;
        name = "";
        address = "";
        type = "";
        fixtureId = 0;
    }
}

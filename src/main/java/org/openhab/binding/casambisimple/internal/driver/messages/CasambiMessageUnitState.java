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
 * The {@link CasambiMessageUnitState} is used to parse unit state messages with gson
 *
 * @author Hein Osenberg - Initial contribution
 * @version V0.1 210827@hpo First version, setup IDE
 *
 *          Not really needed - all functionality already provided by CasambiMessgeUnit
 */
@NonNullByDefault
public class CasambiMessageUnitState {
    public String id;
    public String address;
    public String name;
    public @Nullable String image;
    public @Nullable Integer position;
    public @Nullable Integer fixtureId;
    public @Nullable String firmwareVersion;
    public @Nullable Integer groupId;
    public @Nullable Integer priority;
    public @Nullable Integer scene;
    public Boolean on;
    public Boolean online;
    public @Nullable Integer condition;
    public @Nullable String status;
    public @Nullable Integer activeSceneId;
    public @Nullable Float dimLevel;
    public String type;
    public @Nullable CasambiMessageControl @Nullable [][] controls;

    CasambiMessageUnitState() {
        id = "";
        name = "";
        address = "";
        type = "";
        on = false;
        online = false;
    }
}

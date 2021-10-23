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

/**
 * The {@link CasambiMessageControl} is used to parse control structures with gson
 *
 * @author Hein Osenberg - Initial contribution
 * @version V0.1 210827@hpo First version, setup IDE
 */
@NonNullByDefault
public class CasambiMessageControl {
    public String type; // missing from Photo
    public @Nullable Float value; // Dimmer, CCT
    public @Nullable String source; // Colorsource
    public @Nullable Float x; // Color, Photo
    public @Nullable Float y; // Color, Photo
    public @Nullable String rgb; // Color
    public @Nullable Float tw; // CCT
    public @Nullable Integer min; // CCT
    public @Nullable Integer max; // CCT
    public @Nullable Integer level; // CCT
    public @Nullable String name; // Button
    public @Nullable String buttonLabel; // Button
    public @Nullable String dataname; // Button
    public @Nullable String unit; // Slider, Photo
    public @Nullable String valueType;// Slider
    public @Nullable Integer id; // Button, Dimmer, Rgb, Temperature, Colorsource, Slider
    public @Nullable Boolean readonly;// Button, Dimmer, Rgb, Temperature, Colorsource, Slider

    CasambiMessageControl() {
        type = "";
    };
}

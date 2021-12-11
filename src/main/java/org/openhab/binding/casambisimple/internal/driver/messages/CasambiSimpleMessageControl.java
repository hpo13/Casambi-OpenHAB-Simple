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
 * The {@link CasambiMessageControl} is used to parse control structures with gson
 *
 * @author Hein Osenberg - Initial contribution
 * @version V0.1 210827@hpo First version, setup IDE
 */
@NonNullByDefault
public class CasambiSimpleMessageControl {
    public @Nullable String type; // missing from Photo
    public @Nullable Float value; // Dimmer, CCT, Colorbalance
    public @Nullable String source; // Colorsource
    public @Nullable Float x; // Color(?), Photo
    public @Nullable Float y; // Color(?), Photo
    public @Nullable String rgb; // Color, RGB
    public @Nullable Float tw; // CCT
    public @Nullable Float min; // CCT
    public @Nullable Float max; // CCT
    public @Nullable Float level; // CCT
    public @Nullable Float sat; // Color
    public @Nullable Float hue; // Color
    public @Nullable Float whiteLevel; // ColorBalance
    public @Nullable Float colorLevel; // ColorBalance
    public @Nullable String name; // Button
    public @Nullable String label; // Color
    public @Nullable String buttonLabel; // Button
    public @Nullable String dataname; // Button
    public @Nullable String unit; // Slider, Photo
    public @Nullable String valueType;// Slider
    public @Nullable Integer id; // Button, Dimmer, Rgb, Temperature, Colorsource, Slider
    public @Nullable Boolean readonly;// Button, Dimmer, Rgb, Temperature, Colorsource, Slider

    CasambiSimpleMessageControl() {
        type = "";
    }

    public Boolean isDimmer() {
        return type != null && "Dimmer".equals(type);
    }

    public Boolean isColor() {
        return type != null && "Color".equals(type);
    }

    public Boolean isCCT() {
        return ("Slider".equals(type) && "CCT".equals(name)) || "CCT".equals(type);
    }

    public Boolean isColorbalance() {
        return type != null && "ColorBalance".equals(type);
    }

    public Boolean isWhiteDimmer() {
        return type != null && "Slider".equals(type) && "White Dimmer".equals(name);
    }

    public Float getMin() {
        Float localMin = min;
        if (localMin != null) {
            return localMin;
        } else {
            return (float) 0;
        }
    }

    public Float getMax() {
        Float localMax = max;
        if (localMax != null) {
            return localMax;
        } else {
            return (float) 0;
        }
    }
}

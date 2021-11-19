/* Copyright (c) 2010-2021 Contributors to the openHAB project
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
package org.openhab.binding.casambitest.internal.driver;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * The {@link CasambiDriverConstants} class defines common constants for the driver
 *
 * @author Hein Osenberg - Initial contribution
 */
@NonNullByDefault
public class CasambiDriverConstants {

    //
    public static final String methodUnit = "controlUnit";
    public static final String methodUnits = "controlUnits";
    public static final String methodGroup = "controlGroup";
    public static final String methodScene = "controlScene";
    public static final String methodNetwork = "controlNetwork";

    public static final String targetId = "id";
    public static final String targetIds = "ids";

    public static final String controlWire = "wire";
    public static final String controlMethod = "method";
    public static final String controlValue = "value";
    public static final String controlSource = "source";
    public static final String controlHue = "hue";
    public static final String controlSat = "sat";
    public static final String controlLevel = "level";

    public static final String controlOnOff = "OnOff";
    public static final String controlDimmer = "Dimmer";
    public static final String controlColorTemperature = "ColorTemperature";
    public static final String controlColorBalance = "ColorBalance";
    public static final String controlWhiteLevel = "White Dimmer";
    public static final String controlColorsource = "Colorsource";
    public static final String controlRGB = "RGB";
    public static final String controlTW = "TW";
    public static final String controlXY = "XY";
    public static final String controlTargetControls = "targetControls";

}

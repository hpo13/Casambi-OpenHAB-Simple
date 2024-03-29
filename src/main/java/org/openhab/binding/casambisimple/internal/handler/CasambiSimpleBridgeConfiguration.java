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
package org.openhab.binding.casambisimple.internal.handler;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * The {@link CasambiSimpleBridgeConfiguration} class contains fields mapping thing configuration parameters.
 *
 * @author Hein Osenberg - Initial contribution
 */
@NonNullByDefault
public class CasambiSimpleBridgeConfiguration {
    public String apiKey = "";
    public String userId = "";
    public String userPassword = "";
    public String networkPassword = "";
    public Boolean logMessages = false;
    public String logDir = "";
    public Boolean useRemCmd = false;
    public String remCmdStr = "";
}

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
package org.openhab.binding.casambitest.internal.driver;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Casambi driver system interface - call operating system commands
 *
 * @author Hein Osenberg - Initial contribution
 */

public class CasambiDriverSystem {

    private static Boolean enabled = false;
    private static String command = " ";

    final static Logger logger = LoggerFactory.getLogger(CasambiDriverSystem.class);

    public static final void sendSshCommand() {

        if (enabled) {
            logger.debug("sendSshCommand: sending command {}", command);

            try {
                Process process = Runtime.getRuntime().exec(command);
                BufferedReader stdout = new BufferedReader(new InputStreamReader(process.getInputStream()));
                BufferedReader stderr = new BufferedReader(new InputStreamReader(process.getErrorStream()));

                String oStr;
                while ((oStr = stdout.readLine()) != null) {
                    logger.debug("sendSshCommand: out {}", oStr);
                }
                String eStr;
                while ((eStr = stderr.readLine()) != null) {
                    logger.debug("sendSshCommand: err {}", eStr);
                }
            } catch (Exception e) {
                logger.error("sendSshCommand: exception {}", e);
            }
        }
    }

    public static final void enableSshCommand(Boolean enable, String cmd) {
        logger.trace("enableSshCommand: {}, cmd '{}'", enable, cmd);
        enabled = enable;
        if (enable) {
            command = cmd;
        }
    }
}
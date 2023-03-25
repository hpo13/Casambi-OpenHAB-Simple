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
package org.openhab.binding.casambisimple.internal.driver;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Casambi driver system interface - call operating system commands
 *
 * @author Hein Osenberg - Initial contribution
 */
@NonNullByDefault
public class CasambiSimpleDriverSystem {

    private static Boolean enabled = false;
    private static String command = " ";

    final static Logger logger = LoggerFactory.getLogger(CasambiSimpleDriverSystem.class);

    public static final void sendSshCommand() {
        if (enabled) {
            logger.debug("sendSshCommand: sending command {}", command);
            try {
                Process process = Runtime.getRuntime().exec(command);
                BufferedReader stdout = new BufferedReader(new InputStreamReader(process.getInputStream()));
                BufferedReader stderr = new BufferedReader(new InputStreamReader(process.getErrorStream()));

                String oStr;
                while ((oStr = stdout.readLine()) != null) {
                    logger.debug("sendSshCommand: stdOut {}", oStr);
                }
                String eStr;
                while ((eStr = stderr.readLine()) != null) {
                    logger.debug("sendSshCommand: stdErr {}", eStr);
                }
            } catch (Exception e) {
                logger.error("sendSshCommand: exception {}", e.getMessage());
            }
        }
    }

    public static final void configureSshCommand(Boolean enable, String cmd) {
        logger.trace("enableSshCommand: {}, cmd '{}'", enable, cmd);
        enabled = enable;
        if (enable) {
            command = cmd;
        }
    }
}

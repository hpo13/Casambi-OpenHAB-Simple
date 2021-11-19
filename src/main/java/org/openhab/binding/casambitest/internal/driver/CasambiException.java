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

/**
 * Casambi driver - interface to Casambi websocket API
 *
 * @author Hein Osenberg - Initial contribution
 */
public class CasambiException extends Exception {
    final static long serialVersionUID = 210829110214L; // Use dateTime
    public String message;

    public CasambiException(String msg) {
        super(msg);
        message = msg;
    };

}

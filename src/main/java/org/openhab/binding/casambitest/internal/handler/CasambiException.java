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
package org.openhab.binding.casambitest.internal.handler;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * The {@link CasambiException} signals exceptions within the Casambi binding
 *
 * @author Hein Osenberg - Initial contribution
 */
@NonNullByDefault
public class CasambiException extends Exception {

    private static final long serialVersionUID = 1L;

    public CasambiException() {
        super();
    }

    public CasambiException(String message) {
        super(message);
    }

    public CasambiException(String message, Throwable cause) {
        super(message, cause);
    }

    public CasambiException(Throwable cause) {
        super(cause);
    }
}

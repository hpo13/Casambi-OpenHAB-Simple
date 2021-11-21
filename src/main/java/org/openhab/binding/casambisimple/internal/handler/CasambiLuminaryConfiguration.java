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
package org.openhab.binding.casambisimple.internal.handler;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * The {@link CasambiLuminaryConfiguration} class contains fields mapping thing configuration parameters.
 *
 * @author Hein Osenberg - Initial contribution
 */
@NonNullByDefault
public class CasambiLuminaryConfiguration {
    public Boolean hasDimmer = false;
    public Boolean hasColor = false;
    public Boolean hasCCT = false;
    public Boolean hasColorbalance = false;
    public Float tempMin = (float) 0;
    public Float tempMax = (float) 0;
}

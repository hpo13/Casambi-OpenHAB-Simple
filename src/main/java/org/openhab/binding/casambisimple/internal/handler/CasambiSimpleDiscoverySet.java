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

import java.util.HashSet;
import java.util.Map.Entry;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.thing.Thing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link CasambiSimpleDiscoverySet} keeps count of new things discovered and known things rediscovered
 *
 * @author Hein Osenberg - Initial contribution
 */
@NonNullByDefault
public class CasambiSimpleDiscoverySet {

    final Logger logger = LoggerFactory.getLogger(CasambiSimpleDiscoverySet.class);

    // Instance stuff

    private Set<String> knownThings = new HashSet<>();
    private Set<String> newThings = new HashSet<>();

    private CasambiSimpleThingsById thingMap;

    CasambiSimpleDiscoverySet(CasambiSimpleThingsById thingsById) {
        thingMap = thingsById;
        for (Entry<String, Thing> mapping : thingMap.map.entrySet()) {
            knownThings.add(mapping.getKey());
        }
        logger.trace("CasambiSimpleThingsById: constructor knownThings {}", knownThings);
    }

    public Set<String> getKnown() {
        return knownThings;
    }

    public Set<String> getNew() {
        return newThings;
    }

    public void update(String uid, Integer id) {
        final String uidId = thingMap.uidIdCombine(uid, id);
        if (knownThings.contains(uidId)) {
            logger.trace("updateKnownAndNewThings: uid {} matches, removing from knownThings", uidId);
            knownThings.remove(uidId);
        } else {
            logger.trace("updateKnownAndNewThings: uid {} does not match, adding to newThings", uidId);
            newThings.add(uidId);
        }
    }
}

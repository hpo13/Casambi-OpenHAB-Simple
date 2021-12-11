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

import static org.openhab.binding.casambisimple.internal.CasambiSimpleBindingConstants.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingTypeUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link CasambiSimpleThingsById} maps things to ids and uidIds
 *
 * @author Hein Osenberg - Initial contribution
 */
@NonNullByDefault
public class CasambiSimpleThingsById {

    final Logger logger = LoggerFactory.getLogger(CasambiSimpleThingsById.class);

    // Mapping from uids to things
    public Map<String, Thing> map = new HashMap<>();

    // Add a (new) thing to the mapping
    public void put(@Nullable String uidId, Thing thing) {
        logger.trace("putThingById: uidId {}", uidId);
        if (uidId != null) {
            map.putIfAbsent(uidId, thing);
        }
    }

    // Get thing corresponding to uidId
    public @Nullable Thing getThing(@Nullable String uidId) {
        if (uidId != null) {
            return map.get(uidId);
        } else {
            return null;
        }
    }

    public @Nullable Thing getFirstThing(@Nullable Integer id) {
        if (id != null) {
            for (Entry<String, Thing> uidIdThing : map.entrySet()) {
                String uid = uidIdThing.getKey();
                // UnitUidIdSet.logger.debug("getFirstThingById: looking for {}, got {}", id, uid);
                if (getId(uid).equals(id)) {
                    return uidIdThing.getValue();
                }
            }
        }
        logger.info("getFirstThingById: nothing found for {} in {}", id, map.keySet());
        return null;
    }

    public boolean remove(@Nullable String uidId) {
        if (getThing(uidId) != null) {
            map.remove(uidId);
            return true;
        } else {
            return false;
        }
    }

    public @Nullable Thing getFirstLuminary(@Nullable Integer id) {
        if (id != null) {
            for (Entry<String, Thing> uidIdThing : map.entrySet()) {
                String uid = uidIdThing.getKey();
                // UnitUidIdSet.logger.debug("getFirstThingById: looking for {}, got {}", id, uid);
                if (getId(uid).equals(id)) {
                    return uidIdThing.getValue();
                }
            }
        }
        logger.info("getFirstThingById: nothing found for {} in {}", id, map.keySet());
        return null;
    }

    // Static stuff - convert uid/id ti uidId and uidId to id

    public Integer getId(String uidId) {
        String[] u = uidId.split(":");
        return Integer.parseInt(u[1]);
    }

    public String getUid(String uidId) {
        String[] u = uidId.split(":");
        return u[0];
    }

    public @Nullable ThingTypeUID getType(String uid) {
        if (uid.startsWith("lum")) {
            return THING_TYPE_LUMINARY;
        } else if (uid.startsWith("scn")) {
            return THING_TYPE_SCENE;
        } else if (uid.startsWith("grp")) {
            return THING_TYPE_GROUP;
        } else {
            return null;
        }
    }

    public String uidIdCombine(String uid, Integer id) {
        return String.format("%s:%d", uid, id);
    }
}

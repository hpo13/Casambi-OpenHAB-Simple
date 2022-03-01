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
 * The {@link CasambiSimpleThingsById} maps between OpenHAB things and Casambi ids and uidIds.
 * Casambi assignes integer 'id's to luminaries, scenes and groups. Most of the time a simple
 * mapping beween OpenHAB things and Casambi id's is sufficient. However when a luminary is removed
 * from the Casambi system and later re-added it gets a new id. To account for this, luminaires are
 * tracked through their fixture-id's which do not change. This is used for a more stable mapping for
 * luminaries. This does not work for scenes and groups, because these do not have unique identifiers.
 *
 * FIXME: are we really using the fixture id as intended? Check discovery.
 *
 * @author Hein Osenberg - Initial contribution
 */
@NonNullByDefault
public class CasambiSimpleThingsById {

    final Logger logger = LoggerFactory.getLogger(CasambiSimpleThingsById.class);

    // Mapping from uids to things
    public Map<String, Thing> map = new HashMap<>();

    /**
     * put - Add a (new) thing to the mapping if it does not already exist
     *
     * @param uidId - combination of uid and id. uid is fixture id for luminaries and scn/grp for scenes and groups. id
     *            is the Casambi id.
     * @param thing - Casambi thing object
     */
    public void put(@Nullable String uidId, Thing thing) {
        logger.trace("putThingById: uidId {}", uidId);
        if (uidId != null) {
            map.putIfAbsent(uidId, thing);
        }
    }

    /**
     * getThing returns a thing corresponding to uidId combination
     *
     * @param uidId - combination of uid and id. uid is fixture id for luminaries and scnXX/grpXX for scenes and groups.
     *            id is the Casambi id.
     * @return thing - Casambi thing object or null if not found
     */
    public @Nullable Thing getThing(@Nullable String uidId) {
        if (uidId != null) {
            return map.get(uidId);
        } else {
            return null;
        }
    }

    /**
     * getFirstThing returns a thing based on the id only (without) uid. There may be more than one thing corresponding
     * to an id. Id's for luminaries, scenes and groups are treated seperately (prefix), so there is no confusion
     * between the three categories.
     *
     * @param id - id as specified by the casambi system
     * @return thing - first match in the mapping
     */
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

    /**
     * remove deletes a mapping based on the uidId combination
     *
     * @param uidId - combination of uid and id. uid is fixture id for luminaries and scn/grp for scenes and groups. id
     *            is the Casambi id.
     * @return true, if something was actually removed, false if nothing was found (and removed)
     */
    public boolean remove(@Nullable String uidId) {
        if (getThing(uidId) != null) {
            map.remove(uidId);
            return true;
        } else {
            return false;
        }
    }

    /**
     * getFirstLuninary returns a thing based on the id only (without) uid. There may be more than one thing
     * corresponding
     * to an id.
     *
     * @param id - id as specified by the casambi system
     * @return thing - first match in the mapping
     *
     *         FIXME: is this needed? See getFirstThing()
     */

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

    /**
     * getId returns the id part of a uidId
     *
     * @param uidId - see above
     * @return id part of the uidId
     */
    public Integer getId(String uidId) {
        String[] u = uidId.split(":");
        return Integer.parseInt(u[1]);
    }

    /**
     * getId returns the uid part of a uidId
     *
     * @param uidId - see above
     * @return uid part of the uidId
     */
    public String getUid(String uidId) {
        String[] u = uidId.split(":");
        return u[0];
    }

    /**
     * getType returns the type (lumiary, scene or group) of a uid
     *
     * @param uid - see above
     * @return type for a uid (luminary, scene, group)
     */
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

    /**
     * uidIdCombine constructs a uidId from a uid and and id
     *
     * @param uid
     * @param id
     * @return
     */
    public String uidIdCombine(String uid, Integer id) {
        return String.format("%s:%d", uid, id);
    }
}

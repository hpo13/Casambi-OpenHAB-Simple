package org.openhab.binding.casambitest.internal.handler;

import static org.openhab.binding.casambitest.internal.CasambiBindingConstants.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingTypeUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CasambiThingsById {

    final Logger logger = LoggerFactory.getLogger(CasambiThingsById.class);

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
                if (getId(uid) == id) {
                    return uidIdThing.getValue();
                }
            }
        }
        logger.info("getFirstThingById: nothing found for {} in {}", id, map.keySet());
        return null;
    }

    public @Nullable Thing getFirstLuminary(@Nullable Integer id) {
        if (id != null) {
            for (Entry<String, Thing> uidIdThing : map.entrySet()) {
                String uid = uidIdThing.getKey();
                // UnitUidIdSet.logger.debug("getFirstThingById: looking for {}, got {}", id, uid);
                if (getId(uid) == id) {
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
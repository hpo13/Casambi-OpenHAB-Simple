package org.openhab.binding.casambisimple.internal.handler;

import java.util.HashSet;
import java.util.Map.Entry;
import java.util.Set;

import org.openhab.core.thing.Thing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CasambiDiscoverySet {

    final Logger logger = LoggerFactory.getLogger(CasambiDiscoverySet.class);

    // Instance stuff

    private Set<String> oldThings = new HashSet<>();
    private Set<String> newThings = new HashSet<>();

    private CasambiThingsById thingMap;

    CasambiDiscoverySet(CasambiThingsById thingsById) {
        thingMap = thingsById;
        for (Entry<String, Thing> mapping : thingMap.map.entrySet()) {
            oldThings.add(mapping.getKey());
        }
        logger.trace("CasambiThingsById: constructor oldThings {}", oldThings);
    }

    public Set<String> getOldThings() {
        return oldThings;
    }

    public Set<String> getNewThings() {
        return newThings;
    }

    public void updateOldNew(String uid, Integer id) {
        String uidId = thingMap.uidIdCombine(uid, id);
        if (oldThings.contains(uidId)) {
            logger.trace("updateOldNew: uid {} matches, removing from oldThings", uidId);
            oldThings.remove(uidId);
        } else {
            logger.trace("updateOldNew: uid {} does not match, adding to newThings", uidId);
            newThings.add(uidId);
        }
    }
}

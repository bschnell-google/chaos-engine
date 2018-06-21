package com.gemalto.chaos.container;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class ContainerManager {
    private HashMap<Class<? extends Container>, HashMap<Long, Container>> containerMap = new HashMap<>();

    @Autowired
    ContainerManager () {
    }

    ContainerManager (HashMap<Class<? extends Container>, HashMap<Long, Container>> containerMap) {
        this.containerMap = containerMap;
    }
    /**
     * Returns all containers for a specific container class.
     *
     * @param containerType A class that extends the Container class.
     * @return A Collection of Container objects from the given class.
     */
    Collection<Container> getRoster (Class<? extends Container> containerType) {
        return getContainerTypeMap(containerType).values();
    }

    /**
     * Given a container class, will return persistent HashMap of containers, identified by
     * their serialization.
     *
     * @param containerClass A class which extends the Container class.
     * @return A hashmap of containers, indexed by their serialization.
     */
    private HashMap<Long, Container> getContainerTypeMap (Class<? extends Container> containerClass) {
        return containerMap.computeIfAbsent(containerClass, k -> new HashMap<>());
    }

    /**
     * This will identify if a container has already been identified. If it has, it will
     * return that container from the persistent Container Map. Otherwise, it will add the
     * contained that was passed into the container map.
     *
     * @param container A discovered container.
     * @return A persistent entry for that container that can hold a history.
     */
    public Container getOrCreatePersistentContainer (Container container) {
        HashMap<Long, Container> containerTypeMap = getContainerTypeMap(container.getClass());
        containerTypeMap.putIfAbsent(container.getIdentity(), container);
        return containerTypeMap.get(container.getIdentity());
    }

    public void removeOldContainers (Class<? extends Container> containerClass, List<Container> liveContainers) {
        HashMap<Long, Container> containerTypeMap = getContainerTypeMap(containerClass);
        Set<Container> toRemove = new HashSet<>();
        containerTypeMap.forEach((containerId, container) -> {
            if (!liveContainers.contains(container)) {
                toRemove.add(container);
            }
        });
        for (Container container : toRemove) {
            containerTypeMap.remove(container.getIdentity());
        }
    }
}

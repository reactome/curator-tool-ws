package org.reactome.curation.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.gk.model.InstanceNotFoundException;
import org.gk.model.ReactomeJavaConstants;
import org.reactome.curation.config.CuratorToolEnv;
import org.reactome.curation.model.SimpleInstance;
import org.reactome.curation.util.CuratorToolWSUtils;
import org.reactome.server.graph.domain.model.DatabaseObject;
import org.reactome.server.graph.domain.model.InstanceEdit;
import org.reactome.server.graph.domain.model.Person;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * This component is used to manage InstanceEdit objects so that we may reuse them for a certain period of time.
 * This class is thread-safe and can be safely accessed from multiple threads.
 */
@Component
public class InstanceEditManager {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Logger logger = LoggerFactory.getLogger(InstanceEditManager.class);
    @Autowired
    private CurationService curationService;
    @Autowired
    private CuratorToolEnv toolEnv;
    
    // Cache for storing InstanceEdit objects per person - using ConcurrentHashMap for thread safety
    private final Map<Long, InstanceEdit> instanceEditCache = new ConcurrentHashMap<>();
    
    public InstanceEdit createInstanceEdit(SimpleInstance instance) throws Exception {
        Long personId = instance.getDefaultPersonId();
        if (personId == null) {
            logger.error("Person dbId is not defined!");
            throw new IllegalArgumentException("personId is null");
        }
        return createInstanceEdit(personId);
    }

    /**
     * Creates or reuses an InstanceEdit for the given personId.
     * The InstanceEdit will be reused if one exists in the cache and is within the configured duration.
     * If no cached InstanceEdit exists or the cached one has expired, a new InstanceEdit will be created.
     * This method is thread-safe. Multiple threads may concurrently create InstanceEdits for the same person,
     * but this is acceptable as InstanceEdit creation is lightweight and all created instances are valid.
     * 
     * @param personId the id of the person who created the InstanceEdit
     * @return an InstanceEdit object
     * @throws InstanceNotFoundException if the person with the given id cannot be found
     */
    public InstanceEdit createInstanceEdit(Long personId) throws InstanceNotFoundException {
        // Try to get a cached valid InstanceEdit
        InstanceEdit cached = instanceEditCache.get(personId);
        if (cached != null && isValidCache(cached)) {
            logger.debug("Reusing cached InstanceEdit for person with dbId: " + personId);
            return cached;
        }
        
        // Create a new InstanceEdit if cache miss or expired
        DatabaseObject person = curationService.findById(personId);
        if (person == null) {
            logger.error("Cannot find Person with dbId: " + personId);
            throw new InstanceNotFoundException(ReactomeJavaConstants.Person, personId);
        }
        InstanceEdit ie = new InstanceEdit();
        // Need to specify author and datetime
        ie.setAuthor(Collections.singletonList((Person)person));
        ie.setDateTime(CuratorToolWSUtils.getDateTime());
        // Generate display name for it
        // Technically we should have a place to manage this for all instances
        // However, this work has been moved to the front-end. We just limit it
        // to InstanceEdit here
        String displayName = person.getDisplayName() + ", " + ie.getDateTime().split(" ")[0];
        ie.setDisplayName(displayName);
        
        // Cache the newly created InstanceEdit
        // If race condition occurs, both instances are valid so it's acceptable
        // Have to use put so that we can cache the latest version of InstanceEdit for a Person.
        // Don't use putIfAbsent because we want to update the cache with the latest InstanceEdit for the person.
        instanceEditCache.put(personId, ie);
        logger.debug("Created and cached new InstanceEdit for person with dbId: " + personId);
        
        return ie;
    }
    
    /**
     * Checks if a cached InstanceEdit is still valid based on the configured duration.
     * This method extracts the creation time from InstanceEdit's dateTime field.
     * 
     * @param instanceEdit the InstanceEdit to validate
     * @return true if the cache is still valid, false otherwise
     */
    private boolean isValidCache(InstanceEdit instanceEdit) {
        if (instanceEdit == null) {
            return false;
        }
        // Just in case there is an error and the instance is not saved
        // e.g. Due to the transaction management, instanceEdit may get a dbId, but not really saved
        if (!this.curationService.existsById(instanceEdit.getDbId()))
            return false;
        String dateTimeStr = instanceEdit.getDateTime();
        if (dateTimeStr == null) {
            return false;
        }
        
        try {
            // Parse as LocalDateTime (no zone in string)
            LocalDateTime localDateTime = LocalDateTime.parse(dateTimeStr, FORMATTER);

            // Attach GMT zone explicitly
            ZonedDateTime createdTime = localDateTime.atZone(ZoneId.of("GMT"));

            long createdTimeMillis = createdTime.toInstant().toEpochMilli();
            long currentTime = System.currentTimeMillis();
            long duration = toolEnv.getInstanceEditDuration() * 1000L;
//            long duration = 60 * 10 * 1000L;

            return (currentTime - createdTimeMillis) <= duration;

        } catch (DateTimeParseException e) {
            logger.warn("Failed to parse InstanceEdit dateTime: {}", dateTimeStr, e);
            return false;
        }
    }
    
    /**
     * Clears the InstanceEdit cache for a specific person.
     * This method is thread-safe.
     * 
     * @param personId the id of the person
     */
    public void clearCacheForPerson(Long personId) {
        instanceEditCache.remove(personId);
        logger.debug("Cleared InstanceEdit cache for person with dbId: " + personId);
    }
    
    /**
     * Clears the entire InstanceEdit cache.
     * This method is thread-safe.
     */
    public void clearAllCache() {
        instanceEditCache.clear();
        logger.debug("Cleared entire InstanceEdit cache");
    }

}
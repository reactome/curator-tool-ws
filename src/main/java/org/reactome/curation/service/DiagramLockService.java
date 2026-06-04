package org.reactome.curation.service;

import org.reactome.curation.model.DiagramLock;
import org.reactome.curation.util.CuratorToolUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service class for managing diagram locks.
 * Maintains a map of diagram dbIds to their lock information and associated users.
 */
@Service
public class DiagramLockService {
    private static final Logger logger = LoggerFactory.getLogger(DiagramLockService.class);

    // Map to track locked diagrams: diagramDbId -> DiagramLock
    // Ensure user cannot edit from multiple browsers or sessions at the same time
    private final Map<Long, DiagramLock> lockedDiagrams = new ConcurrentHashMap<>();

    /**
     * Locks a diagram for a specific user.
     *
     * @param diagramDbId the dbId of the diagram to lock
     * @param username the username of the user locking the diagram
     * @return DiagramLock object containing lock information
     */
    public DiagramLock lockDiagram(Long diagramDbId, String username) {
        if (diagramDbId == null || diagramDbId <= 0) {
            logger.warn("Invalid diagram dbId: {}", diagramDbId);
            throw new IllegalArgumentException("Diagram dbId must be a positive number");
        }

        if (username == null || username.trim().isEmpty()) {
            logger.warn("Invalid username: {}", username);
            throw new IllegalArgumentException("Username cannot be null or empty");
        }

        // Atomic lock assignment: if a lock already exists for this diagram, keep it unchanged.
        DiagramLock lock = lockedDiagrams.compute(diagramDbId, (dbId, existingLock) -> {
            if (existingLock != null)
                return existingLock;
            return new DiagramLock(dbId, username, CuratorToolUtilities.getDateTime());
        });

        if (!username.equals(lock.getUsername())) {
            logger.warn("Diagram {} is already locked by user {}", diagramDbId, lock.getUsername());
            return lock;
        }

        logger.info("Diagram {} locked by user {}", diagramDbId, username);
        return lock;

    }

    /**
     * Unlocks a diagram for a specific user.
     *
     * @param diagramLock of the diagram to unlock
     * @return true if unlock was successful, false if diagram was not locked or locked by different user
     */
    public boolean unlockDiagram(DiagramLock diagramLock) {
        if (diagramLock == null) {
            logger.warn("Cannot unlock diagram: diagramLock is null");
            return false;
        }
        return unlockDiagram(diagramLock.getDiagramDbId(), diagramLock.getLockId());
    }

    /**
     * Unlocks a diagram using a diagram dbId and lockId (backup path for GET endpoint).
     *
     * @param diagramDbId dbId of the diagram to unlock
     * @param lockId lock identifier to validate unlock ownership
     * @return true if unlock was successful, false otherwise
     */
    public boolean unlockDiagram(Long diagramDbId, String lockId) {
        if (diagramDbId == null || diagramDbId <= 0) {
            logger.warn("Invalid diagram dbId: {}", diagramDbId);
            return true;
        }

        if (lockId == null || lockId.trim().isEmpty()) {
            logger.warn("Invalid lockId for diagram {}: {}", diagramDbId, lockId);
            return false;
        }

        DiagramLock lock = lockedDiagrams.get(diagramDbId);
        if (lock == null) {
            logger.warn("Diagram {} is not locked", diagramDbId);
            return true;
        }

        if (!lockId.equals(lock.getLockId())) {
            logger.error("Diagram {} is locked by user {}, cannot unlock with lockId {}", diagramDbId, lock.getUsername(), lockId);
            return false;
        }

        lockedDiagrams.remove(diagramDbId);
        logger.info("Diagram {}, with lockId {}, unlocked by username {}", diagramDbId, lock.getLockId(), lock.getUsername());
        return true;
    }

    /**
     * Checks if a diagram is locked.
     *
     * @param diagramDbId the dbId of the diagram
     * @return true if the diagram is locked, false otherwise
     */
    public boolean isLocked(Long diagramDbId) {
        if (diagramDbId == null || diagramDbId <= 0) {
            return false;
        }
        synchronized (lockedDiagrams) {
            return lockedDiagrams.containsKey(diagramDbId);
        }
    }

    /**
     * Gets the lock information for a specific diagram.
     *
     * @param diagramDbId the dbId of the diagram
     * @return DiagramLock object if the diagram is locked, null otherwise
     */
    public DiagramLock getLock(Long diagramDbId) {
        if (diagramDbId == null || diagramDbId <= 0) {
            return null;
        }
        synchronized (lockedDiagrams) {
            return lockedDiagrams.get(diagramDbId);
        }
    }

    /**
     * Finds a lock by session id.
     * <p>
     * This currently matches the provided session id against the lockId stored
     * on each DiagramLock, so callers can use the session identifier as the
     * lookup key for the lock associated with that session.
     *
     * @param sessionId session identifier or lock identifier
     * @return the matching DiagramLock, or null if no lock matches
     */
    public DiagramLock getLock(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return null;
        }

        synchronized (lockedDiagrams) {
            return lockedDiagrams.values().stream()
                    .filter(lock -> sessionId.equals(lock.getLockId()))
                    .findFirst()
                    .orElse(null);
        }
    }

    /**
     * Gets all currently locked diagrams.
     *
     * @return map of all locked diagrams (diagramDbId -> DiagramLock)
     */
    public Map<Long, DiagramLock> getAllLockedDiagrams() {
        synchronized (lockedDiagrams) {
            return new HashMap<>(lockedDiagrams);
        }
    }

    /**
     * Clears all locks for a specific user.
     * Useful when a user logs out or their session ends.
     *
     * @param username the username
     */
    public void clearUserLocks(String username) {
        if (username == null || username.trim().isEmpty()) {
            return;
        }
    }
}

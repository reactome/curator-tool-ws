package org.reactome.curation.service;

import org.reactome.curation.model.DiagramLock;
import org.reactome.curation.repository.DiagramLockRepository;
import org.reactome.curation.util.CuratorToolWSUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Service class for managing diagram locks persisted in H2 database.
 * Handles locking/unlocking diagrams, validating permissions, and tracking lock ownership.
 */
@Service
@Transactional
public class DiagramLockService {
    private static final Logger logger = LoggerFactory.getLogger(DiagramLockService.class);
    private static final long MIN_DIAGRAM_ID = 1L;
    private static final String INVALID_DIAGRAM_ID_MSG = "Diagram dbId must be a positive number";
    private static final String INVALID_USERNAME_MSG = "Username cannot be null or empty";
    private static final String LOCK_LOST_MSG = "Lock lost due to concurrent modification";

    private final DiagramLockRepository diagramLockRepository;

    public DiagramLockService(DiagramLockRepository diagramLockRepository) {
        this.diagramLockRepository = diagramLockRepository;
    }

    /**
     * Locks a diagram for a specific user. Ensures only one user holds a lock per diagram.
     *
     * @param diagramDbId the dbId of the diagram to lock
     * @param username the username of the user locking the diagram
     * @return the DiagramLock object (existing or newly created)
     * @throws IllegalArgumentException if diagramDbId or username is invalid
     */
    public DiagramLock lockDiagram(Long diagramDbId, String username) {
        validateDiagramDbId(diagramDbId);
        validateUsername(username);

        Optional<DiagramLock> existingLock = loadLock(diagramDbId);
        if (existingLock.isPresent()) {
            DiagramLock lock = existingLock.get();
            if (!username.equals(lock.getUsername())) {
                logger.warn("Diagram {} is already locked by user {}", diagramDbId, lock.getUsername());
            }
            return lock;
        }

        DiagramLock newLock = new DiagramLock(diagramDbId, "", username, CuratorToolWSUtils.getDateTime());
        DiagramLock persistedLock = insertLockWithRaceHandling(newLock);

        logger.info("Diagram {} locked by user {}", diagramDbId, username);
        return persistedLock;
    }

    /**
     * Unlocks a diagram using a DiagramLock object.
     *
     * @param diagramLock the lock object containing unlock credentials
     * @return true if unlock was successful, false otherwise
     */
    public boolean unlockDiagram(DiagramLock diagramLock) {
        if (diagramLock == null) {
            logger.warn("Cannot unlock diagram: diagramLock is null");
            return false;
        }
        return unlockDiagramInternal(diagramLock.getDiagramDbId(),
                                     diagramLock.getLockId(),
                                     diagramLock.getUsername());
    }

    /**
     * Unlocks a diagram with optional username validation.
     *
     * @param diagramDbId the diagram id
     * @param lockId the lock identifier to validate
     * @param username optional username to verify ownership
     * @return true if unlock was successful, false otherwise
     */
    public boolean unlockDiagram(Long diagramDbId, String lockId, String username) {
        return unlockDiagramInternal(diagramDbId, lockId, username);
    }

    /**
     * Checks if a diagram is locked.
     *
     * @param diagramDbId the dbId of the diagram
     * @return true if the diagram is locked, false otherwise
     */
    public boolean isLocked(Long diagramDbId) {
        return existsLock(diagramDbId);
    }

    /**
     * Gets the lock information for a specific diagram.
     *
     * @param diagramDbId the dbId of the diagram
     * @return DiagramLock object if the diagram is locked, null otherwise
     */
    public DiagramLock getLock(Long diagramDbId) {
        return loadLock(diagramDbId).orElse(null);
    }

    /**
     * Gets all currently locked diagrams.
     *
     * @return map of all locked diagrams (diagramDbId -> DiagramLock)
     */
    public Map<Long, DiagramLock> getAllLockedDiagrams() {
        return loadAllLocks().stream().collect(Collectors.toMap(
                DiagramLock::getDiagramDbId,
                Function.identity(),
                (first, second) -> first));
    }

    /**
     * Get all DiagramLocks for one specific user.
     */
    public List<DiagramLock> getUserLocks(String username) {
        validateUsername(username);
        return diagramLockRepository.findByUsername(username);
    }

    // ==================== Repository Access Methods ====================

    public Optional<DiagramLock> loadLock(Long diagramDbId) {
        return isValidDbId(diagramDbId) ? diagramLockRepository.load(diagramDbId) : Optional.empty();
    }

    public List<DiagramLock> loadAllLocks() {
        return diagramLockRepository.load();
    }

    public boolean existsLock(Long diagramDbId) {
        return isValidDbId(diagramDbId) && diagramLockRepository.existsByDiagramDbId(diagramDbId);
    }

    public void deleteLock(Long diagramDbId) {
        if (isValidDbId(diagramDbId)) {
            diagramLockRepository.delete(diagramDbId);
        }
    }

    public void deleteLock(DiagramLock diagramLock) {
        if (diagramLock != null) {
            deleteLock(diagramLock.getDiagramDbId());
        }
    }

    public void deleteAllLocks() {
        diagramLockRepository.deleteAll();
    }

    public long countLocks() {
        return diagramLockRepository.count();
    }

    // ==================== Insertion Methods ====================

    /**
     * Inserts a new lock with the given diagram id and username.
     *
     * @param diagramDbId the diagram id
     * @param username the username of the lock holder
     * @return the persisted lock
     * @throws IllegalArgumentException if inputs are invalid
     */
    public DiagramLock insertLock(Long diagramDbId, String username) {
        validateDiagramDbId(diagramDbId);
        validateUsername(username);
        return insertLock(new DiagramLock(diagramDbId, "", username, CuratorToolWSUtils.getDateTime()));
    }

    /**
     * Inserts a new lock. Ensures lock has lockId and lockedAt timestamp.
     *
     * @param diagramLock the lock to insert
     * @return the persisted lock
     * @throws IllegalArgumentException if lock is null or invalid
     */
    public DiagramLock insertLock(DiagramLock diagramLock) {
        if (diagramLock == null) {
            throw new IllegalArgumentException("DiagramLock cannot be null");
        }
        validateDiagramDbId(diagramLock.getDiagramDbId());
        validateUsername(diagramLock.getUsername());
        ensureLockProperties(diagramLock);
        return diagramLockRepository.add(diagramLock);
    }

    // ==================== Private Helper Methods ====================

    /**
     * Core unlock logic. Validates lock ownership via lockId and optional username.
     */
    private boolean unlockDiagramInternal(Long diagramDbId, String lockId, String username) {
        if (!isValidString(lockId)) {
            logger.error("Cannot unlock diagram {}: lockId is missing or empty", diagramDbId);
            return false;
        }

        DiagramLock lock = getLock(diagramDbId);
        if (lock == null) {
            logger.warn("Diagram {} is not locked", diagramDbId);
            return true;
        }

        if (!validateLockId(diagramDbId, lock, lockId)) {
            return false;
        }

        if (!validateLockUsername(diagramDbId, lock, username)) {
            return false;
        }

        deleteLock(diagramDbId);
        if (!isLockRemovalConfirmed(diagramDbId, lockId)) {
            logger.error("Failed to remove lock {} for diagram {}", lockId, diagramDbId);
            return false;
        }

        logger.info("Diagram {} unlocked by user {}", diagramDbId, lock.getUsername());
        return true;
    }

    /**
     * Confirms the specific lock was removed. A new lock with a different id may exist due to concurrent re-locking.
     */
    private boolean isLockRemovalConfirmed(Long diagramDbId, String removedLockId) {
        Optional<DiagramLock> remainingLock = loadLock(diagramDbId);
        return remainingLock.isEmpty() || !removedLockId.equals(remainingLock.get().getLockId());
    }

    /**
     * Handles race condition when inserting new lock. If insert fails due to duplicate,
     * reload the lock that was inserted by the competing request.
     */
    private DiagramLock insertLockWithRaceHandling(DiagramLock newLock) {
        try {
            ensureLockProperties(newLock);
            return diagramLockRepository.add(newLock);
        }
        catch (DataIntegrityViolationException e) {
            logger.debug("Concurrent lock insertion detected for diagram {}, reloading",
                        newLock.getDiagramDbId());
            return loadLock(newLock.getDiagramDbId())
                    .orElseThrow(() -> new IllegalStateException(LOCK_LOST_MSG, e));
        }
    }

    /**
     * Validates that the lockId matches the stored lock.
     */
    private boolean validateLockId(Long diagramDbId, DiagramLock lock, String lockId) {
        if (!lockId.equals(lock.getLockId())) {
            logger.error("Invalid lockId for diagram {} (locked by {})", diagramDbId, lock.getUsername());
            return false;
        }
        return true;
    }

    /**
     * Validates that the username matches the lock holder (if provided).
     */
    private boolean validateLockUsername(Long diagramDbId, DiagramLock lock, String username) {
        if (isValidString(username) && !username.equals(lock.getUsername())) {
            logger.error("Diagram {} locked by user {}, cannot unlock as user {}",
                        diagramDbId, lock.getUsername(), username);
            return false;
        }
        return true;
    }

    /**
     * Ensures lock has generated lockId and lockedAt timestamp before persistence.
     */
    private void ensureLockProperties(DiagramLock lock) {
        if (!isValidString(lock.getLockedAt())) {
            lock.setLockedAt(CuratorToolWSUtils.getDateTime());
        }
        if (!isValidString(lock.getLockId())) {
            lock.setLockId(UUID.randomUUID().toString());
        }
    }

    /**
     * Validates that a diagram ID is a positive number.
     * @throws IllegalArgumentException if invalid
     */
    private void validateDiagramDbId(Long diagramDbId) {
        if (!isValidDbId(diagramDbId)) {
            throw new IllegalArgumentException(INVALID_DIAGRAM_ID_MSG);
        }
    }

    /**
     * Validates that username is not null or blank.
     * @throws IllegalArgumentException if invalid
     */
    private void validateUsername(String username) {
        if (!isValidString(username)) {
            throw new IllegalArgumentException(INVALID_USERNAME_MSG);
        }
    }

    /**
     * Checks if a diagram ID is valid (positive integer).
     */
    private boolean isValidDbId(Long dbId) {
        return dbId != null && dbId >= MIN_DIAGRAM_ID;
    }

    /**
     * Checks if a string is not null, blank, or whitespace-only.
     */
    private boolean isValidString(String str) {
        return str != null && !str.trim().isEmpty();
    }

}

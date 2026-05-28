package org.reactome.curation.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * Model class representing a lock on a diagram.
 * Tracks which user has locked a specific diagram and when.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(Include.NON_NULL)
public class DiagramLock {
    private Long diagramDbId;
    private String username;
    private String lockedAt;
    private String status; // "LOCKED" or "UNLOCKED"
    private String lockId; // Unique identifier for the lock, useful for tracking and unlocking

    public DiagramLock(Long diagramDbId, String username, String lockedAt) {
        this.diagramDbId = diagramDbId;
        this.username = username;
        this.lockedAt = lockedAt;
        this.status = "LOCKED";
        this.lockId = UUID.randomUUID().toString(); // Generate a unique lock ID
    }
}

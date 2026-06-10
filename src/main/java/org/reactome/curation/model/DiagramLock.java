package org.reactome.curation.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;

import java.util.UUID;

/**
 * Model class representing a lock on a diagram.
 * Tracks which user has locked a specific diagram and when.
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "diagram_locks")
@JsonInclude(Include.NON_NULL)
public class DiagramLock {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // id of the PathwayDiagram instance
    @Column(unique = true, nullable = false)
    private Long diagramDbId;
    // Keep the display name to reduce api traffic.
    @Column(nullable = false)
    private String diagramDisplayName;
    // The user. This should be the user who logs in. It is not the default person.
    @Column(nullable = false)
    private String username;
    // Timestamp to keep track
    @Column(nullable = false)
    private String lockedAt;
    @Transient
    private Boolean hasBackupDiagram;

    @Column(unique = true, nullable = false)
    private String lockId; // Unique identifier for the lock, useful for tracking and unlocking

    public DiagramLock(Long diagramDbId, String diagramDisplayName, String username, String lockedAt) {
        this.diagramDbId = diagramDbId;
        this.diagramDisplayName = diagramDisplayName;
        this.username = username;
        this.lockedAt = lockedAt;
        this.lockId = UUID.randomUUID().toString(); // Generate a unique lock ID
    }
}

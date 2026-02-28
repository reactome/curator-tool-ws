package org.reactome.curation.user.model;

import java.time.Instant;

import lombok.Data;

/**
 * Model a refresh token to avoid repeated logging with a shorter access JWT token. 
 * Since this is for a small team, we will keep RefreshToken in memory and not persist it to a database.
 * Therefore, the data structure is simpler than a typical refresh token implementation that would be stored in a database.
 */
@Data
public class RefreshToken {

    private String token;

    private Instant expiresAt;

    private Instant lastUsedAt;

    // In practice, this may not be needed.
    private boolean revoked;

    private User user;
}
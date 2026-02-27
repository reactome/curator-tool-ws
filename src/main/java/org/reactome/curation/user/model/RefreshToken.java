package org.reactome.curation.user.model;

import lombok.Data;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.ManyToOne;
import java.time.Instant;
import java.util.UUID;

@Entity
@Data
public class RefreshToken {

    @Id
    @GeneratedValue
    private UUID id;

    private String token;

    private Instant expiresAt;

    private Instant lastUsedAt;

    private boolean revoked;

    @ManyToOne
    private User user;
}

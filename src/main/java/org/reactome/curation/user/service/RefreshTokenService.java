package org.reactome.curation.user.service;

import org.reactome.curation.user.model.RefreshToken;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory management of refresh tokens for a small team of users.
 * This service stores and manages refresh token lifecycle without persisting to a database.
 */
@Service
public class RefreshTokenService {
    
    @Autowired
    private JwtService jwtService;
    
    // In-memory storage: token string -> RefreshToken
    private final Map<String, RefreshToken> refreshTokenStore = new ConcurrentHashMap<>();
    
    /**
     * Store a refresh token in memory.
     * 
     * @param token the JWT refresh token string
     * @return the stored RefreshToken object
     */
    public RefreshToken saveRefreshToken(String token) {
        RefreshToken refreshToken = new RefreshToken();
        Instant now = Instant.now();
        refreshToken.setLastUsedAt(now);
        refreshToken.setExpiresAt(now.plusMillis(jwtService.getRefreshTokenExpirationTime()));
        refreshToken.setRevoked(false);
        refreshToken.setUsername(jwtService.extractUsername(token));
        
        refreshTokenStore.put(token, refreshToken);
        return refreshToken;
    }
    
    /**
     * Find a refresh token by its token string.
     * 
     * @param token the JWT refresh token string
     * @return Optional containing the RefreshToken if found and not expired/revoked
     */
    public Optional<RefreshToken> findByToken(String token) {
        RefreshToken refreshToken = refreshTokenStore.get(token);
        if (refreshToken == null) {
            return Optional.empty();
        }
        
        // Check if token is expired or revoked
        if (refreshToken.isRevoked() || 
            isTokenExpired(refreshToken) || 
            isTokenIdle(refreshToken) || 
            !isTheSameUser(token, refreshToken)) {
            refreshToken.setRevoked(true);
            refreshTokenStore.remove(token);
            return Optional.empty();
        }
        
        // Update last used time
        refreshToken.setLastUsedAt(Instant.now());
        return Optional.of(refreshToken);
    }
    
    /**
     * Revoke a refresh token by marking it as revoked.
     * 
     * @param token the JWT refresh token string
     */
    public void revokeToken(String token) {
        RefreshToken refreshToken = refreshTokenStore.get(token);
        if (refreshToken != null) {
            refreshToken.setRevoked(true);
            refreshTokenStore.remove(token);
        }
    }
    
    /**
     * Check if a refresh token is expired.
     * 
     * @param refreshToken the RefreshToken to check
     * @return true if the token is expired, false otherwise
     */
    private boolean isTokenExpired(RefreshToken refreshToken) {
        return Instant.now().isAfter(refreshToken.getExpiresAt());
    }
    
    /**
     * Check if a refresh token is idle.
     * This is an additional check to prevent tokens from being used after a long period of inactivity.
     * 
     * @param refreshToken the RefreshToken to check
     * @return true if the token is idle, false otherwise
     */
    private boolean isTokenIdle(RefreshToken refreshToken) {
        return Instant.now().isAfter(refreshToken.getLastUsedAt().plusMillis(jwtService.getIdleTokenTime()));
    }
    
    private boolean isTheSameUser(String refreshToken, 
                                  RefreshToken tokenInStore) {
        String username = jwtService.extractUsername(refreshToken);
        return tokenInStore.getUsername().equals(username);
    }
    
    /**
     * Clean up expired tokens from the store.
     * This should be called periodically to prevent memory bloat.
     * However, this method is not called anyway. 
     */
    public void cleanupExpiredTokens() {
        refreshTokenStore.entrySet().removeIf(entry -> 
            isTokenExpired(entry.getValue()) || entry.getValue().isRevoked());
    }
}

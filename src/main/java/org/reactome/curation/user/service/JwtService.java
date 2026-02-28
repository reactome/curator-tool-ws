package org.reactome.curation.user.service;

import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

@Service
public class JwtService {

    // There should be only one key for the whole application when it starts.
    private static final SignatureAlgorithm signatureAlg = SignatureAlgorithm.HS512;
    private static final SecretKey key = Keys.secretKeyFor(signatureAlg);
    
    // JWT token timing configurations read from application.properties
    @Value("${jwt.access-token-time:600000}")
    private long accessTokenTime; // Default: 10 minutes in milliseconds
    
    @Value("${jwt.idle-token-time:1200000}")
    private long idleTokenTime; // Default: 20 minutes in milliseconds
    
    @Value("${jwt.refresh-token-time:604800000}")
    private long refreshTokenTime; // Default: 7 days in milliseconds
    
    public static final String REFRESH_TOKEN_COOKIE_NAME = "refresh_token";

    public String generateAccessToken(String username) {
        return generateToken(username, accessTokenTime);
    }

    private String generateToken(String username, long expirationTime) {
        return Jwts.builder()
                .setSubject(username)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + expirationTime))
                .signWith(key, signatureAlg)
                .compact();
    }

    /**
     * Generate a refresh token and return it with expiration time.
     * 
     * @param username the username for the token
     * @return the JWT refresh token string
     */
    public String generateRefreshToken(String username) {
        return generateToken(username, refreshTokenTime);
    }
    
    /**
     * Get the refresh token expiration time in milliseconds.
     * 
     * @return the refresh token expiration time
     */
    public long getRefreshTokenExpirationTime() {
        return refreshTokenTime;
    }
    
    /**
     * Get the access token expiration time in milliseconds.
     * 
     * @return the access token expiration time
     */
    public long getAccessTokenExpirationTime() {
        return accessTokenTime;
    }
    
    /**
     * Get the idle token expiration time in milliseconds.
     * 
     * @return the idle token expiration time
     */
    public long getIdleTokenTime() {
        return idleTokenTime;
    }

    /**
     * Perform validation and get the user name.
     * @param token
     * @return
     */
    public String extractUsername(String token) {
        return Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token).getBody().getSubject();
    }
}
package org.reactome.curation.user.service;

import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

@Service
public class JwtService {

    // There should be only one key for the whole application when it starts.
    private static final SignatureAlgorithm signatureAlg = SignatureAlgorithm.HS512;
    private static final SecretKey key = Keys.secretKeyFor(signatureAlg);
    // The following configurations may need to move to an external configuration file 
    private static final long ACCESS_TOKEN_TIME = 1000 * 10; // 10 minutes
    public static final long IDLE_TOKEN_TIME = 1000 * 60 * 10 * 2; // 20 minutes
    public static final long REFRESH_TOKEN_TIME = 1000 * 60 * 24 * 7; // 7 days
    public static final String REFRESH_TOKEN_COOKIE_NAME = "refresh_token";

    public String generateAccessToken(String username) {
        return generateToken(username, ACCESS_TOKEN_TIME);
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
     * The refresh token expires at 2x the access token duration.
     * 
     * @param username the username for the token
     * @return the JWT refresh token string
     */
    public String generateRefreshToken(String username) {
        return generateToken(username, REFRESH_TOKEN_TIME);
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
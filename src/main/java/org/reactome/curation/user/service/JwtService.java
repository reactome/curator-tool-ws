package org.reactome.curation.user.service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.reactome.curation.user.model.RefreshToken;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {

    // There should be only one key for the whole application when it starts.
    private static final SignatureAlgorithm signatureAlg = SignatureAlgorithm.HS512;
    private static final SecretKey key = Keys.secretKeyFor(signatureAlg);
    private static final long AUTHENTICATION_TIME = 1000 * 60 * 10; // 10 minutes
    private static final long MAX_TIME = 1000 * 60 * 60 * 24 * 7; // 7 days

    // TODO: move time constraints here

    public String generateAuthenticationToken(String username) {
        return Jwts.builder()
                .setSubject(username)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + AUTHENTICATION_TIME))
                .signWith(key, signatureAlg)
                .compact();
    }

    public RefreshToken generateRefreshToken(String username) {
        String token = Jwts.builder()
                .setSubject(username)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + MAX_TIME))
                .signWith(key, signatureAlg)
                .compact();

        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setToken(token);
        refreshToken.setLastUsedAt(Instant.now());
        return refreshToken;
    }


    /**
     * Perform validation and get the user name.
     * @param token
     * @return
     */
    public  String extractUsername(String token) {
        return Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token).getBody().getSubject();
    }
}

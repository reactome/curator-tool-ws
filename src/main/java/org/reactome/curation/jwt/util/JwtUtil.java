package org.reactome.curation.jwt.util;

import java.util.Date;

import javax.crypto.SecretKey;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

public class JwtUtil {
   
//    @Value("${jwt.secret}")
//    private String SECRET_KEY;
    // There should be only one key for the whole application when it starts.
    private static final SignatureAlgorithm signatureAlg = SignatureAlgorithm.HS512;
    private static final SecretKey key = Keys.secretKeyFor(signatureAlg);
    private static final long EXPIRATION_TIME = 1000 * 60 * 60 * 8; // 8 hours  

    public static String generateToken(String username) {
        return Jwts.builder()
                .setSubject(username)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + EXPIRATION_TIME))
                .signWith(key, signatureAlg)
                .compact();
    }
    

    /**
     * Perform validation and get the user name. 
     * @param token
     * @return
     */
    public static String extractUsername(String token) {
        return Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token).getBody().getSubject();
    }

    
}
package org.reactome.curation.jwt.util;

import java.util.Date;

import javax.crypto.SecretKey;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

public class JwtUtil {
   
//    @Value("${jwt.secret}")
//    private String SECRET_KEY;
    private static final SignatureAlgorithm signatureAlg = SignatureAlgorithm.HS512;
    private static final long EXPIRATION_TIME = 1000 * 60 * 60 * 8; // 8 hours  

    public static String generateToken(String username) {
        SecretKey key = getKey();
        return Jwts.builder()
                .setSubject(username)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + EXPIRATION_TIME))
                .signWith(key, signatureAlg)
                .compact();
    }
    
    private static SecretKey getKey() {
//        byte[] keyBytes = Base64.getDecoder().decode(SECRET_KEY);
//        return Keys.hmacShaKeyFor(keyBytes);
        return Keys.secretKeyFor(signatureAlg);
    }

    /**
     * Perform validation and get the user name. 
     * @param token
     * @return
     */
    public static String extractUsername(String token) {
        return Jwts.parserBuilder().setSigningKey(getKey()).build().parseClaimsJws(token).getBody().getSubject();
    }

    
}
package org.reactome.curation.jwt.util;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.crypto.SecretKey;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.neo4j.driver.*;

public class JwtUtil {
   
//    @Value("${jwt.secret}")
//    private String SECRET_KEY;
    // There should be only one key for the whole application when it starts.
    private static final SignatureAlgorithm signatureAlg = SignatureAlgorithm.HS512;
    private static final SecretKey key = Keys.secretKeyFor(signatureAlg);
    private static final long EXPIRATION_TIME = 1000 * 60 * 60 * 8; // 8 hours  
    
    public static String generateToken(String username, String password) {
        runSimpleQuery(username, password);
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

    public static Driver createDriver(String username, String password) {
        return GraphDatabase.driver(
                "bolt://localhost:7687",
                AuthTokens.basic(username, password)
        );
    }

    public static List<String> runSimpleQuery(String username, String password) {
        try (Driver driver = createDriver(username, password);
             Session session = driver.session()) {

            Result result = session.run("MATCH (n) RETURN n LIMIT 5");

            List<String> nodeSummaries = new ArrayList<>();
            while (result.hasNext()) {
                Record record = result.next();
                nodeSummaries.add(record.get("n").toString());
            }

            return nodeSummaries;
        }
    }
}
package org.reactome.curation.user.service;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.reactome.curation.user.model.RefreshToken;
import org.reactome.curation.user.model.User;
import org.reactome.curation.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserService {
    private static final Logger logger = LoggerFactory.getLogger(UserService.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;
    private static final long INACTIVITY_TIME = 1000 * 60 * 20; // 20 minutes
    // Using a map to get unique refresh key based on user session (username + id generated in front-end)
    private final ConcurrentMap<String, RefreshToken> user2token = new ConcurrentHashMap<>();

    /**
     * Authenticate a User.
     * @param username, String
     * @return boolean
     */
    public boolean authenticate(String username, String rawPassword, String id) {
        User existedUser = userRepository.findByUsername(username).get();
        if (existedUser == null) {
            logger.error("Cannot find user: " + username);
            return false;
        }
        existedUser.setUuId(id);
        // The password in the existed user should be encoded
        return passwordEncoder.matches(rawPassword, existedUser.getPassword());
    }

    
    public User saveUser(String username, String rawPassword, String role) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setRole(role);
        return userRepository.save(user);
    }
    

    public Optional<User> findUserByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    public String generateAuthenticationToken(User user){
       return this.jwtService.generateAuthenticationToken(user.getUsername());
    }

    public RefreshToken createRefreshToken(User userDetails) {
        User user = userRepository
                .findByUsername(userDetails.getUsername())
                .orElseThrow();

        RefreshToken token =  this.jwtService.generateRefreshToken(user.getUsername());

        token.setRevoked(false);

        // Cache the user to their refresh token
        this.user2token.put(userDetails.getUuId(), token);
        return token;
    }

    public RefreshToken refreshAccessToken(String id) {

        RefreshToken token = this.user2token.get(id); // get from the hashmap

        if (token.isRevoked())
            throw new RuntimeException("Token revoked");

        if (token.getLastUsedAt()
                .plus(INACTIVITY_TIME, ChronoUnit.MILLIS)
                .isBefore(Instant.now()))
            throw new RuntimeException("Session expired");

        token.setLastUsedAt(Instant.now());

        RefreshToken newToken = jwtService.generateRefreshToken(token.getUser().getUsername());
        this.user2token.replace(id, token, newToken);

        return newToken;
    }
}

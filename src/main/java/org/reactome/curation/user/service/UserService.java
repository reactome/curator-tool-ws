package org.reactome.curation.user.service;
import java.util.Optional;

import org.reactome.curation.user.model.User;
import org.reactome.curation.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserService {
    private static final Logger logger = LoggerFactory.getLogger(UserService.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /**
     * Authenticate a User.
     * @param user
     * @return
     */
    public boolean authenticate(String username, String rawPassword) {
        User existedUser = userRepository.findByUsername(username).get();
        if (existedUser == null) {
            logger.error("Cannot find user: " + username);
            return false;
        }
        // The password in the existed user should be encoded
        return passwordEncoder.matches(rawPassword, existedUser.getPassword());
    }
   
    
    public User saveUser(String username, String rawPassword) {
        return saveUser(username, rawPassword, null);
    }
    
    
    public User saveUser(String username, String rawPassword, String role) {
        Optional<User> existingUser = userRepository.findByUsername(username);
        if (existingUser.isPresent()) {
            throw new IllegalArgumentException("User already exists: " + username);
        }
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setRole(role);
        return userRepository.save(user);
    }
    

    public Optional<User> findUserByUsername(String username) {
        return userRepository.findByUsername(username);
    }
    
    public User changePassword(String username, String rawPassword) {
        User user = userRepository.findByUsername(username)
                                  .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));
        user.setPassword(passwordEncoder.encode(rawPassword));
        return userRepository.save(user);
    }
    
    public void deleteUser(String username) {
        User user = userRepository.findByUsername(username)
                                  .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));
        userRepository.delete(user);
    }
}
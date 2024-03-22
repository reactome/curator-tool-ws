package org.reactome.curation.user.service;

import javassist.NotFoundException;
import lombok.AllArgsConstructor;
import org.reactome.curation.service.CurationService;
import org.reactome.curation.user.model.User;
import org.reactome.curation.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.*;
import java.util.stream.Collectors;

@AllArgsConstructor
@Service
public class UserService {
    private static final Logger logger = LoggerFactory.getLogger(UserService.class);

    private final UserRepository repo;

    public User searchByEmail(String email) {
        return repo.findByEmail(email);
    }

    public User findUserById(final UUID id){
        User user = null;
        try { 
            user = repo.findById(id);
        }
        catch(Error e) {
            logger.info("User by id " + id + " was not found" + e);
        }
        return user;
    }

    public User createUser(User user, String password)
            throws NoSuchAlgorithmException, IOException {

        if (password.isBlank()) throw new IllegalArgumentException(
                "Password is required"
        );

//        var existsEmail = repo.selectExistsEmail(user.getEmail());
//
//        try{existsEmail.equals(true);}
//        catch (Error e){logger.error("Email " + user.getEmail() + " taken");}

        byte[] salt = createSalt();
        byte[] hashedPassword = createPasswordHash(password, salt);

        user.setPassword(password);
        user.setStoredSalt(salt);
        user.setStoredHash(hashedPassword);
        System.out.println("hashService" + user.getStoredHash());

        repo.save(user);

        return user;
    }

//    public void updateUser(UUID id, UserDto userDto, String password)
//            throws NoSuchAlgorithmException {
//        var user = findOrThrow(id);
//        var userParam = convertToEntity(userDto);
//
//        user.setEmail(userParam.getEmail());
//        user.setMobileNumber(userParam.getMobileNumber());
//
//        if (!password.isBlank()) {
//            byte[] salt = createSalt();
//            byte[] hashedPassword = createPasswordHash(password, salt);
//
//            user.setStoredSalt(salt);
//            user.setStoredHash(hashedPassword);
//        }
//
//        repo.save(user);
//    }
//
//    public void removeUserById(UUID id) {
//        findOrThrow(id);
//        repo.deleteById(id);
//    }


    private byte[] createSalt() {
        var random = new SecureRandom();
        var salt = new byte[128];
        random.nextBytes(salt);

        return salt;
    }

    private byte[] createPasswordHash(String password, byte[] salt)
            throws NoSuchAlgorithmException {
        var md = MessageDigest.getInstance("SHA-512");
        md.update(salt);
        System.out.println(md.digest(password.getBytes(StandardCharsets.UTF_8)));
        return md.digest(password.getBytes(StandardCharsets.UTF_8));
    }

    private User findOrThrow(final UUID id) {
        try {
            return repo
                    .findById(id);
        } catch (Error e) {
            logger.info("User by id " + id + " was not found", e);

        }
        return null;
    }

    public Iterable<User> findAllUsers() {
        return new Iterable<User>() {
            @Override
            public Iterator<User> iterator() {
                return null;
            }
        };
    }
}

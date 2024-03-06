package org.reactome.curation.user.service;

import javassist.NotFoundException;
import lombok.AllArgsConstructor;
import org.reactome.curation.service.CurationService;
import org.reactome.curation.user.model.User;
import org.reactome.curation.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@AllArgsConstructor
@Service
public class UserService {
    private static final Logger logger = LoggerFactory.getLogger(UserService.class);

    private final UserRepository repo;

    public User searchByEmail(String email) {
        return repo.findByEmail(email);
    }

//    public User findUserById(final UUID id) throws NotFoundException {
//        var user = repo
//                .findById(id)
//                .orElseThrow(
//                        () -> new NotFoundException("User by id " + id + " was not found")
//                );
//
//        return user;
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

    public User findUserById(UUID id) {
        return new User();
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

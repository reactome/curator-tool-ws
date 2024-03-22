package org.reactome.curation.jwt.service;

import lombok.AllArgsConstructor;
import org.reactome.curation.jwt.model.UserPrincipal;
import org.reactome.curation.user.model.User;
import org.reactome.curation.user.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Service
@AllArgsConstructor
public class ApplicationUserDetailsService implements UserDetailsService {

    @Autowired
    private UserService userService;

    @Override
    public UserDetails loadUserByUsername(String email)
            throws UsernameNotFoundException {
        return new UserPrincipal(userService.searchByEmail(email));
    }

    public User authenticate(String email, String password)
            throws NoSuchAlgorithmException {
        if (
                email.isEmpty() || password.isEmpty()
        ) throw new BadCredentialsException("Unauthorized");

        var user = userService.searchByEmail(email);

        if (user == null) throw new BadCredentialsException("Unauthorized");

        var verified = verifyPasswordHash(
                password,
                user.getStoredHash(),
                user.getStoredSalt()
        );

        //if (!verified) throw new BadCredentialsException("Unauthorized");
        System.out.println("user" + user);

        return user;
    }

    private Boolean verifyPasswordHash(
            String password,
            byte[] storedHash,
            byte[] storedSalt
    ) throws NoSuchAlgorithmException {
        if (
                password.isBlank() || password.isEmpty()
        ) throw new IllegalArgumentException(
                "Password cannot be empty or whitespace only string."
        );

//        if (storedHash.length != 64) throw new IllegalArgumentException(
//                "Invalid length of password hash (64 bytes expected)"
//        );
//
//        if (storedSalt.length != 128) throw new IllegalArgumentException(
//                "Invalid length of password salt (64 bytes expected)."
//        );

        var md = MessageDigest.getInstance("SHA-512");
        md.update(storedSalt);

        var computedHash = md.digest(password.getBytes(StandardCharsets.UTF_8));

        for (int i = 0; i < computedHash.length; i++) {
            if (computedHash[i] != storedHash[i]) return false;
        }

        // The above for loop is the same as below

        return MessageDigest.isEqual(computedHash, storedHash);
    }
}

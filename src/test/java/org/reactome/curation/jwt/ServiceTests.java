package org.reactome.curation.jwt;

import org.junit.jupiter.api.Test;
import org.reactome.curation.service.CurationService;
import org.reactome.curation.user.model.User;
import org.reactome.curation.user.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;

@SpringBootTest
class ServiceTests {

    private static final Logger logger = LoggerFactory.getLogger(ServiceTests.class);

    @Autowired
    private UserService userService;

    @Test
    void contextLoads() {
    }

    @Test
    void testCreateUser() throws NoSuchAlgorithmException, IOException {
        String email = "test@gmail.com";
        String mobilePhone = "123-456-7890";
        String password = "password";
        User user = new User(email, mobilePhone);
        userService.createUser(user, password);
    }
}

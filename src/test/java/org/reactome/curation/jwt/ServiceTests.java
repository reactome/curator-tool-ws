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

//TODO: Need to create a command line application so that we can add/remove users locally without 
// creating a register component at the front end. This is for security concern.
@SpringBootTest
class ServiceTests {

    private static final Logger logger = LoggerFactory.getLogger(ServiceTests.class);

    @Autowired
    private UserService userService;

    @Test
    void contextLoads() {
    }

    @Test
    void testQueryUser() {
        String username = "test";
        User user = userService.findUserByUsername(username).get();
        logger.info("Find user for username: " + user);
    }
    
    @Test
    void createUser() {
        // There is another test user: test and password
//        String username = "test_1";
//        String password = "test_1_pwd";
        String username = "deidre";
        String password = "beavers";
        String role = "curator";
        User user = userService.saveUser(username, password, role);
        logger.info("User saved: " + user);
    }
    
}

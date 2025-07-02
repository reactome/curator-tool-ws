package org.reactome.curation.jwt;

import org.junit.jupiter.api.Test;
import org.reactome.curation.jwt.controller.AuthenticateController;
import org.reactome.curation.user.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ControllerTests {

    private static final Logger logger = LoggerFactory.getLogger(ControllerTests.class);

    @Autowired
    private AuthenticateController controller;

    @Test
    void contextLoads() {
    }


    @Test
    public void testAuthenticate() throws Exception {
        String userName = "deidre";
        String password = "beavers";
        
        User request = new User(userName, password);

        String token = controller.authenticate(request);
        
        logger.info("Token for authenticate: " + token);
    }
    

}

package org.reactome.curation.jwt.controller;

import lombok.AllArgsConstructor;
import org.reactome.curation.controller.CurationController;
import org.reactome.curation.jwt.model.AuthenticationRequest;
import org.reactome.curation.jwt.model.AuthenticationResponse;
import org.reactome.curation.jwt.service.ApplicationUserDetailsService;
import org.reactome.curation.jwt.util.JwtUtil;
import org.reactome.curation.model.SimpleInstance;
import org.reactome.curation.user.model.User;
import org.reactome.server.graph.domain.model.DatabaseObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.*;

@RestController
@AllArgsConstructor
@RequestMapping(value = "curatortool/api")
@ResponseStatus(HttpStatus.CREATED)
@CrossOrigin
public class AuthenticateController {
    private static final Logger logger = LoggerFactory.getLogger(AuthenticateController.class);

    @Autowired
    private AuthenticationManager authenticationManager;
    @Autowired
    private JwtUtil jwtTokenUtil;
    @Autowired
    private ApplicationUserDetailsService userDetailsService;

@PostMapping("/authenticate")
public AuthenticationResponse authenticate(@RequestBody AuthenticationRequest req)
            throws Exception {User user;
        try {
            user = userDetailsService.authenticate(req.getEmail(), req.getPassword());
        } catch (BadCredentialsException e) {
            throw new Exception("Incorrect username or password", e);
        }

        var userDetails = userDetailsService.loadUserByUsername(user.getEmail());

        System.out.println(userDetails);
        var jwt = jwtTokenUtil.generateToken(userDetails);

        return new AuthenticationResponse(jwt);
    }
}


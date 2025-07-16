package org.reactome.curation.jwt.controller;

import org.reactome.curation.jwt.util.JwtUtil;
import org.reactome.curation.user.model.User;
import org.reactome.curation.user.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("api")
@CrossOrigin
public class AuthenticateController {
    
    @Autowired
    private UserService userService;




//    @PostMapping("/authenticate")
//    public ResponseEntity<?> testNeo4jLogin(@RequestBody User loginRequest) {
//        try {
//            List<String> results = neo4jService.runSimpleQuery(loginRequest.getUsername(), loginRequest.getPassword());
//            return ResponseEntity.ok(results);
//        } catch (Exception e) {
//            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid login or error: " + e.getMessage());
//        }
//    }
    
    @PostMapping("/authenticate")
    public String authenticate(@RequestBody User user) {
        if (userService.authenticate(user.getUsername(), user.getPassword())) {
            return JwtUtil.generateToken(user.getUsername(), user.getPassword());
        }
        throw new BadCredentialsException("Invalid username or password");

    }
    
    // We will not support register from the web for the time being. Registration will be handled locally
    // by developers using a command line tool.
//    @PostMapping("/register")
//    public String register(@RequestBody User user) {
//        user.setPassword(passwordEncoder.encode(user.getPassword()));
//        userRepository.save(user);
//        return "User registered";
//    }
    
}


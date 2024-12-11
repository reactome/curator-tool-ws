package org.reactome.curation.jwt.controller;

import org.reactome.curation.jwt.util.JwtUtil;
import org.reactome.curation.user.model.User;
import org.reactome.curation.user.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("api")
@CrossOrigin
public class AuthenticateController {
    
    @Autowired
    private UserService userService;
    
    
    @PostMapping("/authenticate")
    public String authenticate(@RequestBody User user) {
        if (userService.authenticate(user.getUsername(), user.getPassword())) {
            return JwtUtil.generateToken(user.getUsername());
        }
        throw new BadCredentialsException("Invalid username or password");
        // Somehow I cannot make the following work: circular reference. Therefore, use
        // our own authentication above - GW
//        Authentication authentication = authenticationManager.authenticate(
//                new UsernamePasswordAuthenticationToken(request.getUsername(), 
//                        request.getPassword())
//        );
//        SecurityContextHolder.getContext().setAuthentication(authentication);
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


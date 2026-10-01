package org.reactome.curation.controller;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.reactome.curation.user.model.User;
import org.reactome.curation.user.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lets other Reactome services (e.g. curator-tool-llm) ask who a bearer token belongs to and what
 * role they hold, so ws stays the single authority for users and roles and no signing secret has to
 * be shared. JwtRequestFilter has already rejected a missing, invalid or expired token before this
 * runs, and has put the username in the security context.
 */
@RestController
@RequestMapping("api/auth")
public class AuthVerifyController {

    @Autowired
    private UserService userService;

    /**
     * @return {"username": ..., "role": ...} for the caller's token; 401 if there is no authenticated user.
     */
    @GetMapping("/verify")
    public ResponseEntity<Map<String, String>> verify() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        Optional<User> user = userService.findUserByUsername(auth.getName());
        if (user.isEmpty())
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        Map<String, String> body = new LinkedHashMap<>();
        body.put("username", user.get().getUsername());
        body.put("role", user.get().getRole() == null ? "" : user.get().getRole());
        return ResponseEntity.ok(body);
    }
}

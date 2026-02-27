package org.reactome.curation.jwt.controller;

import org.reactome.curation.user.model.RefreshToken;
import org.reactome.curation.user.model.User;
import org.reactome.curation.user.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.time.Duration;

@RestController
@RequestMapping("api")
@CrossOrigin
public class AuthenticateController {

    @Autowired
    private UserService userService;

    @PostMapping("/authenticate")
    public ResponseEntity<String> authenticate(@RequestBody User user, HttpServletResponse response) {
        if (userService.authenticate(user.getUsername(), user.getPassword(), user.getUuId())) {
            String accessToken =
                    userService.generateAuthenticationToken(user);

            RefreshToken refreshToken =
                    userService.createRefreshToken(user);

            ResponseCookie cookie = this.addRefreshToCookie(refreshToken.getToken());
            response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

            return ResponseEntity.ok(accessToken);
        }
        throw new BadCredentialsException("Invalid username or password");

        // Somehow I cannot make the following work: circular reference. Therefore, use
        // our own authentication above. The SecurityContextHolder will be handled by the
        // jwtfilter - GW
//        Authentication authentication = authenticationManager.authenticate(
//                new UsernamePasswordAuthenticationToken(request.getUsername(),
//                        request.getPassword())
//        );
//        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(
            @CookieValue(name = "refresh_token", required = false)
            String id,
            HttpServletResponse response
    ){

        RefreshToken newAccessToken =
                userService.refreshAccessToken(
                        id
                );

        return ResponseEntity.ok(
                new AuthResponse(newAccessToken.getToken(), null)
        );
    }

    private ResponseCookie addRefreshToCookie(String token) {
       return ResponseCookie.from("refresh_token", token)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path("/auth/refresh")
                .maxAge(Duration.ofHours(12))
                .build();
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


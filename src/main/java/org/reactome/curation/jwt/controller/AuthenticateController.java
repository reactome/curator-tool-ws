package org.reactome.curation.jwt.controller;

import java.time.Duration;

import javax.servlet.http.HttpServletResponse;

import org.reactome.curation.user.model.User;
import org.reactome.curation.user.service.JwtService;
import org.reactome.curation.user.service.RefreshTokenService;
import org.reactome.curation.user.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("api/auth")
// Need to use external configuration file to set allowed origins. Otherwise, we have to hard code the allowed origins here and it is not ideal.
// This may not work if we change the frontend server URL in the future. 
@CrossOrigin(origins = {"http://localhost:4200", "https://curator.reactome.org", "https://newcurator.reactome.org"}, allowCredentials = "true")
public class AuthenticateController {

    @Autowired
    private UserService userService;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private RefreshTokenService refreshTokenService;
    
    @Value("${isProduction:false}")
    private Boolean isProduction = false; // Set to true in production environment to enable secure cookies

    /**
     * Authenticate user and return both access and refresh tokens.
     * 
     * @param user the user credentials
     * @return AuthenticationResponse containing accessToken, refreshToken, and expiresIn
     * @throws BadCredentialsException if authentication fails
     */
    // TODO: add auditing to track users logging in and loggin out
    @PostMapping("/login")
    public String authenticate(@RequestBody User user,
                               HttpServletResponse response) {
        if (!userService.authenticate(user.getUsername(), user.getPassword())) {
            throw new BadCredentialsException("Invalid username or password");
        }

        return generateTokens(user.getUsername(), response);
    }

    private String generateTokens(String userName, HttpServletResponse response) {
        try {
            // Generate access token
            String accessToken = jwtService.generateAccessToken(userName);

            // Generate refresh token and store it in memory
            String refreshToken = jwtService.generateRefreshToken(userName);
            refreshTokenService.saveRefreshToken(refreshToken);

            // Set refresh token in HttpOnly cookie
            ResponseCookie cookie = this.addRefreshToCookie(refreshToken);
            response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

            return accessToken;
        }
        catch(Exception e) {
            throw new BadCredentialsException("Authentication failed: " + e.getMessage());
        }
    }

    private ResponseCookie addRefreshToCookie(String token) {
        return ResponseCookie.from(JwtService.REFRESH_TOKEN_COOKIE_NAME, token)
                .httpOnly(true)
                .secure(isProduction) // Set secure flag based on environment
                .sameSite("Lax")
                .path("/api")
                .maxAge(Duration.ofHours(12))
                .build();
    }

    /**
     * Build a cookie that expires the refresh token cookie on the client. All attributes
     * (name, path, httpOnly, secure, sameSite) must match {@link #addRefreshToCookie} so the
     * browser recognizes it as the same cookie and removes it; maxAge(0) triggers deletion.
     */
    private ResponseCookie clearRefreshCookie() {
        return ResponseCookie.from(JwtService.REFRESH_TOKEN_COOKIE_NAME, "")
                .httpOnly(true)
                .secure(isProduction)
                .sameSite("Lax")
                .path("/api")
                .maxAge(0)
                .build();
    }

    /**
     * Log the user out: revoke the server-side refresh token (if present) and expire the
     * HttpOnly refresh cookie on the client. The refresh cookie is HttpOnly, so it can only
     * be cleared server-side; a client-only logout leaves a stale cookie that later gets
     * replayed on /refresh. This is idempotent — it succeeds even when no cookie is sent.
     */
    @PostMapping("/logout")
    public void logout(@CookieValue(name = JwtService.REFRESH_TOKEN_COOKIE_NAME, required = false) String refreshToken,
                       HttpServletResponse response) {
        if (refreshToken != null && !refreshToken.trim().isEmpty()) {
            refreshTokenService.revokeToken(refreshToken);
        }
        ResponseCookie cookie = this.clearRefreshCookie();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    /**
     * Refresh the access token using a valid refresh token.
     * 
     * @param refreshTokenRequest the request containing the refresh token
     * @return AuthenticationResponse containing a new accessToken and the same refreshToken
     * @throws BadCredentialsException if the refresh token is invalid or expired
     */
    @PostMapping("/refresh")
    public String refreshToken(@CookieValue(name=JwtService.REFRESH_TOKEN_COOKIE_NAME) String refreshToken,
                               HttpServletResponse response) {
        if (refreshToken == null || refreshToken.trim().isEmpty()) {
            throw new BadCredentialsException("Refresh token is required");
        }

        // Verify the refresh token is valid
        if (!refreshTokenService.findByToken(refreshToken).isPresent()) {
            throw new BadCredentialsException("Invalid or expired refresh token");
        }
        try {
            // Extract username from refresh token
            String username = jwtService.extractUsername(refreshToken);
            // We'd like to remove refresh token from cookie after use.
            refreshTokenService.revokeToken(refreshToken);
            return generateTokens(username, response);
        }
        catch(Exception e) {
            throw new BadCredentialsException("Failed to refresh token: " + e.getMessage());
        }
    }
}
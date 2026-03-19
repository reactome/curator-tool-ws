package org.reactome.curation.jwt.controller;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.reactome.curation.user.model.User;
import org.reactome.curation.user.service.JwtService;
import org.reactome.curation.user.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtRequestFilter extends OncePerRequestFilter {
    private static final Logger logger = LoggerFactory.getLogger(JwtRequestFilter.class);

    // Exact URI paths that write to the database or pathway diagrams.
    // Path-variable segments are matched via startsWith() in isWriteRequest().
    private static final Set<String> WRITE_URIS = Set.of(
            "/api/curation/commit",
            "/api/curation/delete",
            "/api/curation/deleteByDeleted",
            "/api/curation/uploadCyNetwork/"
    );
    
    @Autowired
    private JwtService jwtService;
    @Autowired
    private UserService userService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, 
                                    HttpServletResponse response, 
                                    FilterChain chain)  throws ServletException, IOException {
        // Check if the request is for authenticate or register
        if (request.getRequestURI().equals("/api/auth/login") || 
            request.getRequestURI().equals("/api/auth/refresh")) {
            chain.doFilter(request, response); // Skip JWT filter
            return;
        }
        String authorizationHeader = request.getHeader("Authorization");
        if (authorizationHeader == null || authorizationHeader.trim().length() == 0)
            throw new BadCredentialsException("Cannot find jwt token.");

        String username = null;
        
        if (authorizationHeader.startsWith("Bearer ")) {
            String token = authorizationHeader.substring(7);
            // Just assume the token is generated from us
            try {
                username = jwtService.extractUsername(token);
            }
            catch(Exception e) {
                logger.error("JwtRequestFilter.doFilterInternal: " + e.getMessage());
            }
        }
        if (username == null) {
            // Need to see if there is a better way.
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED); // Send 401 status
            response.getWriter().write("Unauthorized - Try login again.");
            response.getWriter().flush();
            return;
//            throw new BadCredentialsException("Wrong jwt token.");
        }
        
        Optional<User> user = userService.findUserByUsername(username);
        if (user.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED); // Send 401 status
            response.getWriter().write("Unauthorized - User not found.");
            response.getWriter().flush();
            return;
        }
        
        // Make sure user has the write privilege to update the database and diagram.
        if (isWriteRequest(request.getRequestURI())) {
            String role = user.get().getRole();
            if (role == null || !role.equalsIgnoreCase("curator")) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN); // 403
                response.getWriter().write("Forbidden - curator role required.");
                response.getWriter().flush();
                return;
            }
        }
        
        // If username is valid, set the authentication in the security context
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            // Set the authentication in the security context
            // Make sure to use this version of constructor even though null is passed
            // to avoid circular calling. It is fine since we have validated jwt already.
            // This constructor will set authentication to true. Therefore, no need to 
            // authenticate via AOP in spring.
            UsernamePasswordAuthenticationToken authentication = 
                new UsernamePasswordAuthenticationToken(username, null, null);
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        chain.doFilter(request, response);
    }
    
    /**
     * Returns true when the request URI targets an endpoint that writes to
     * the database or updates pathway diagrams.  Read-only endpoints, staging
     * file operations and QA checks are intentionally excluded.
     */
    private boolean isWriteRequest(String requestURI) {
        for (String writeUri : WRITE_URIS) {
            // Exact match for fixed paths; prefix match for paths with variables.
            if (requestURI.equals(writeUri) || requestURI.startsWith(writeUri)) {
                return true;
            }
        }
        return false;
    }
}
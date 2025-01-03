package org.reactome.curation.jwt.filters;

import java.io.IOException;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.reactome.curation.jwt.util.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtRequestFilter extends OncePerRequestFilter {
    private static final Logger logger = LoggerFactory.getLogger(JwtRequestFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, 
                                    HttpServletResponse response, 
                                    FilterChain chain)  throws ServletException, IOException {
        // Check if the request is for authenticate or register
        if (request.getRequestURI().equals("/api/authenticate") || request.getRequestURI().equals("/api/register")) {
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
                username = JwtUtil.extractUsername(token);
            }
            catch(Exception e) {
                logger.error("JwtRequestFilter.doFilterInternal: " + e.getMessage(), e);
            }
        }
        if (username == null) {
            // Need to see if there is a better way.
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED); // Send 401 status
            response.getWriter().write("Invalid or missing JWT token");
            response.getWriter().flush();
            return;
//            throw new BadCredentialsException("Wrong jwt token.");
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
}

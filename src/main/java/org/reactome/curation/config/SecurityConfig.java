package org.reactome.curation.config;

import org.reactome.curation.jwt.controller.JwtRequestFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {
    
    @Autowired
    private JwtRequestFilter jwtRequestFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // Have to enable cors here. Otherwise it cannot work!
        http.cors().and().csrf().disable() // We are build a stateless, jwt based app and it should be safe to disable csrf here.
            .authorizeRequests()
            .antMatchers("/api/authenticate", "/api/refresh", "/api/register").permitAll()  // Allow unauthenticated access to login and register
            .anyRequest().authenticated();
        http.addFilterBefore(jwtRequestFilter, UsernamePasswordAuthenticationFilter.class); // Add custom JWT filter
        // Turn off the jwt security check for the time being
//        http.csrf().disable().authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    // NB: Though we have not used this bean in our code, however, it is needed to create this manager so that
    // we have our own user name and password 
    @Bean
    public AuthenticationManager authenticationManager(HttpSecurity http) throws Exception {
        return http.getSharedObject(AuthenticationManagerBuilder.class).build();
    }

//    @Bean
//    public JwtRequestFilter jwtAuthenticationFilter() {
//        return new JwtRequestFilter();
//    }
    
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

//    @Override
//    protected void configure(HttpSecurity http) throws Exception {
//        http
//                // first chain
//                .csrf()
//                .disable()
//                // second chain
//                .antMatcher("/**")
//                .authorizeRequests()
//                // third chain
//                .antMatchers("/**")
//                .permitAll()
//                // fourth chain
//                .and()
//                .sessionManagement()
//                .sessionCreationPolicy(SessionCreationPolicy.STATELESS);
////        http.addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
//    }
}

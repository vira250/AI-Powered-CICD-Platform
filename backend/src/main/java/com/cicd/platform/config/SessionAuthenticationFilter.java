package com.cicd.platform.config;

import com.cicd.platform.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class SessionAuthenticationFilter extends OncePerRequestFilter {

    private final UserRepository userRepository;

    public SessionAuthenticationFilter(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String authHeader = request.getHeader("Authorization");
            Long userId = null;

            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String token = authHeader.substring(7).trim();
                if (!token.isEmpty()) {
                    var userOpt = userRepository.findBySessionToken(token);
                    if (userOpt.isPresent()) {
                        userId = userOpt.get().getId();
                        // Also attach userId to request attribute for easy retrieval in controllers
                        request.setAttribute("authenticatedUserId", userId);
                    }
                }
            }

            if (userId == null) {
                var session = request.getSession(false);
                if (session != null) {
                    userId = (Long) session.getAttribute("userId");
                }
            }

            if (userId != null) {
                UserDetails principal = User.withUsername(userId.toString())
                        .password("")
                        .authorities(List.of(new SimpleGrantedAuthority("ROLE_USER")))
                        .build();

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                principal,
                                null,
                                principal.getAuthorities());
                authentication.setDetails(request);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }

        filterChain.doFilter(request, response);
    }
}
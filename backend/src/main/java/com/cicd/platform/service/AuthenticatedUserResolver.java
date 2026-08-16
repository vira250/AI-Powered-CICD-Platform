package com.cicd.platform.service;

import com.cicd.platform.model.User;
import com.cicd.platform.repository.UserRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthenticatedUserResolver {

    private final UserRepository userRepository;

    public AuthenticatedUserResolver(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User resolve(HttpSession session) {
        Long userId = extractUserId(session);
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }

        return userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user not found"));
    }

    private Long extractUserId(HttpSession session) {
        if (session != null) {
            Object sessionUserId = session.getAttribute("userId");
            if (sessionUserId instanceof Long longUserId) {
                return longUserId;
            }
            if (sessionUserId instanceof Number numberUserId) {
                return numberUserId.longValue();
            }
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()) {
            try {
                return Long.parseLong(authentication.getName());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        return null;
    }
}
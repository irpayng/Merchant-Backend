package com.tms.report.core.security;

import com.tms.report.modules.auth.service.SessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Validates JWTs and populates the Spring Security context with the merchant
 * login principal. Loads the account fresh from the database each request
 * (simple and correct for the dashboard's traffic); revoked/deactivated
 * accounts stop authenticating on their next call.
 *
 * <p>
 * Also validates that the session ID embedded in the token is still active,
 * which enforces single-session-per-user: when a user logs in on a new device,
 * their previous session is invalidated, and subsequent requests with the old
 * token will fail session validation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final MerchantUserDetailsService merchantUserDetailsService;
    private final SessionService sessionService;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");

        // Support token from query param for SSE endpoints (EventSource can't send
        // headers).
        String jwt = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            jwt = authHeader.substring(7);
        } else if (request.getRequestURI().endsWith("/stream")) {
            String tokenParam = request.getParameter("token");
            if (tokenParam != null && !tokenParam.isEmpty()) {
                jwt = tokenParam;
            }
        }

        if (jwt == null) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            String username = jwtService.extractUsername(jwt);
            String sessionId = jwtService.extractSessionId(jwt);

            if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                UserDetails userDetails = merchantUserDetailsService.loadUserByUsername(username);

                // Validate both JWT signature/expiry and session existence
                if (jwtService.isTokenValid(jwt, userDetails) && isSessionValid(sessionId)) {
                    var authToken = new UsernamePasswordAuthenticationToken(userDetails, null,
                            userDetails.getAuthorities());
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                }
            }
        } catch (Exception ignored) {
            // Invalid/expired token or unknown user — proceed unauthenticated.
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Check if the session ID is valid. Tokens without a session ID (legacy tokens
     * issued before session tracking) are rejected.
     */
    private boolean isSessionValid(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            // Legacy token without session ID — reject to enforce re-login
            log.debug("Token rejected: no session ID present");
            return false;
        }
        boolean valid = sessionService.isSessionValid(sessionId);
        if (!valid) {
            log.debug("Token rejected: session {} is no longer valid (logged out or superseded)", sessionId);
        }
        return valid;
    }
}

package com.tms.report.modules.audit.filter;

import com.tms.report.core.security.MerchantUserDetails;
import com.tms.report.modules.audit.model.AuditLog;
import com.tms.report.modules.audit.repository.AuditLogRepository;
import com.tms.report.modules.merchantuser.model.MerchantUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Filter that captures audit logs for all non-GET requests made by
 * authenticated merchant users. Logs are written asynchronously after the
 * response is sent.
 *
 * <p>
 * This filter is registered in the Spring Security filter chain AFTER the
 * JwtAuthenticationFilter so that the SecurityContext is populated when we
 * access the authenticated user. It is NOT auto-scanned as a servlet filter
 * (no @Component) to avoid double registration.
 */
@Slf4j
@RequiredArgsConstructor
public class AuditLoggingFilter extends OncePerRequestFilter {

    private final AuditLogRepository auditLogRepository;

    /**
     * Paths to exclude from audit logging (health checks, static resources, etc.).
     */
    private static final Set<String> EXCLUDED_PATHS = Set.of("/health", "/actuator", "/favicon.ico", "/error");

    /** Sensitive fields to redact from request body. */
    private static final Set<String> SENSITIVE_FIELDS = Set.of("password", "pin", "otp", "token", "secret",
            "card_number", "cvv", "pan");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String method = request.getMethod();
        String path = request.getRequestURI();

        log.debug("AuditLoggingFilter: method={}, path={}", method, path);

        // Skip GET requests and excluded paths
        if ("GET".equalsIgnoreCase(method) || isExcludedPath(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        log.debug("AuditLoggingFilter: processing non-GET request for path={}", path);

        // Wrap request and response to capture body
        ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request,
                request.getContentLength() > 0 ? request.getContentLength() : 1024);
        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);

        try {
            filterChain.doFilter(wrappedRequest, wrappedResponse);
        } finally {
            // Log the action after the response is ready
            logAction(wrappedRequest, wrappedResponse, method, path);

            // Copy response body to actual response
            wrappedResponse.copyBodyToResponse();
        }
    }

    private boolean isExcludedPath(String path) {
        return EXCLUDED_PATHS.stream().anyMatch(path::startsWith);
    }

    private void logAction(ContentCachingRequestWrapper request, ContentCachingResponseWrapper response, String method,
            String path) {

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        log.info("AuditLoggingFilter.logAction: auth={}, path={}",
                auth != null ? auth.getClass().getSimpleName() : "null", path);

        if (auth == null || !(auth.getPrincipal() instanceof MerchantUserDetails details)) {
            // Not authenticated or not a merchant user — skip
            log.info("AuditLoggingFilter: skipping - auth is null or not MerchantUserDetails. Principal type: {}",
                    auth != null ? auth.getPrincipal().getClass().getName() : "null");
            return;
        }

        MerchantUser user = details.getMerchantUser();
        if (user == null) {
            log.info("AuditLoggingFilter: skipping - MerchantUser is null");
            return;
        }

        log.info("AuditLoggingFilter: logging action for user={}, path={}", user.getEmail(), path);

        String requestBody = getRequestBody(request);
        String sanitizedBody = sanitizeBody(requestBody);
        String action = deriveAction(method, path);

        AuditLog auditLog = AuditLog.builder().merchantId(user.getMerchantId()).userId(user.getId())
                .userName(user.getName()).userEmail(user.getEmail()).userRole(user.getRole()).method(method)
                .path(truncate(path, 500)).action(action).requestBody(truncate(sanitizedBody, 10000))
                .responseStatus(response.getStatus()).ipAddress(resolveClientIp(request))
                .userAgent(truncate(request.getHeader("User-Agent"), 500)).build();

        auditLogRepository.save(auditLog);
        log.info("AuditLoggingFilter: successfully saved audit log for path={}", path);
    }

    private String getRequestBody(ContentCachingRequestWrapper request) {
        byte[] buf = request.getContentAsByteArray();
        if (buf.length == 0) {
            return null;
        }
        return new String(buf, StandardCharsets.UTF_8);
    }

    /**
     * Redact sensitive fields from the request body. Works with JSON payloads.
     */
    private String sanitizeBody(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        String sanitized = body;
        for (String field : SENSITIVE_FIELDS) {
            // Match "field": "value" or "field":"value" patterns
            Pattern pattern = Pattern.compile("(\"" + field + "\"\\s*:\\s*)\"[^\"]*\"", Pattern.CASE_INSENSITIVE);
            sanitized = pattern.matcher(sanitized).replaceAll("$1\"[REDACTED]\"");
        }
        return sanitized;
    }

    /**
     * Derive a human-readable action from the HTTP method and path.
     */
    private String deriveAction(String method, String path) {
        // Extract the resource name from the path
        String[] parts = path.split("/");
        if (parts.length < 2) {
            return method.toLowerCase();
        }

        // Find the main resource (first non-empty, non-numeric segment after /api if
        // present)
        String resource = null;
        String subAction = null;
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty() || "api".equals(part)) {
                continue;
            }
            if (resource == null && !isNumeric(part)) {
                resource = part;
            } else if (resource != null && !isNumeric(part)) {
                subAction = part;
            }
        }

        if (resource == null) {
            return method.toLowerCase();
        }

        // Build action description
        String action = switch (method.toUpperCase()) {
            case "POST" -> subAction != null ? subAction.replace("-", " ") : "create " + singularize(resource);
            case "PUT", "PATCH" -> subAction != null ? subAction.replace("-", " ") : "update " + singularize(resource);
            case "DELETE" -> "delete " + singularize(resource);
            default -> method.toLowerCase() + " " + resource;
        };

        return capitalize(action);
    }

    private boolean isNumeric(String str) {
        try {
            Long.parseLong(str);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String singularize(String word) {
        if (word.endsWith("ies")) {
            return word.substring(0, word.length() - 3) + "y";
        }
        if (word.endsWith("s") && !word.endsWith("ss")) {
            return word.substring(0, word.length() - 1);
        }
        return word;
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return truncate(forwarded.split(",")[0].trim(), 64);
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return truncate(realIp.trim(), 64);
        }
        return truncate(request.getRemoteAddr(), 64);
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }
}

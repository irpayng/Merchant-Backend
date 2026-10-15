package com.tms.report.modules.audit.filter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
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
public class AuditLoggingFilter extends OncePerRequestFilter {

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public AuditLoggingFilter(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Paths to exclude from audit logging (health checks, static resources, etc.).
     */
    private static final Set<String> EXCLUDED_PATHS = Set.of("/health", "/actuator", "/favicon.ico", "/error");

    /** Sensitive fields to redact from request body. */
    private static final Set<String> SENSITIVE_FIELDS = Set.of("password", "pin", "otp", "token", "secret",
            "card_number", "cvv", "pan");

    /** Fields to extract from response for building description. */
    private static final Set<String> ENTITY_ID_FIELDS = Set.of("id", "serial", "serial_number", "serialNumber",
            "account_number", "accountNumber", "name", "email", "reference", "terminal_id", "terminalId");

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
        log.debug("AuditLoggingFilter.logAction: auth={}, path={}",
                auth != null ? auth.getClass().getSimpleName() : "null", path);

        if (auth == null || !(auth.getPrincipal() instanceof MerchantUserDetails details)) {
            // Not authenticated or not a merchant user — skip
            log.debug("AuditLoggingFilter: skipping - auth is null or not MerchantUserDetails. Principal type: {}",
                    auth != null ? auth.getPrincipal().getClass().getName() : "null");
            return;
        }

        MerchantUser user = details.getMerchantUser();
        if (user == null) {
            log.debug("AuditLoggingFilter: skipping - MerchantUser is null");
            return;
        }

        log.debug("AuditLoggingFilter: logging action for user={}, path={}", user.getEmail(), path);

        String requestBody = getRequestBody(request);
        String responseBody = getResponseBody(response);
        String sanitizedRequestBody = sanitizeBody(requestBody);

        // Derive action with entity context from response
        String action = deriveActionWithContext(method, path, requestBody, responseBody);

        AuditLog auditLog = AuditLog.builder().merchantId(user.getMerchantId()).userId(user.getId())
                .userName(user.getName()).userEmail(user.getEmail()).userRole(user.getRole()).method(method)
                .path(truncate(path, 500)).action(action).requestBody(truncate(sanitizedRequestBody, 10000))
                .responseStatus(response.getStatus()).ipAddress(resolveClientIp(request))
                .userAgent(truncate(request.getHeader("User-Agent"), 500)).build();

        auditLogRepository.save(auditLog);
        log.debug("AuditLoggingFilter: successfully saved audit log for path={}", path);
    }

    private String getRequestBody(ContentCachingRequestWrapper request) {
        byte[] buf = request.getContentAsByteArray();
        if (buf.length == 0) {
            return null;
        }
        return new String(buf, StandardCharsets.UTF_8);
    }

    private String getResponseBody(ContentCachingResponseWrapper response) {
        byte[] buf = response.getContentAsByteArray();
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
     * Derive a human-readable action from the HTTP method, path, and response body.
     * The response body often contains the entity details we need for a meaningful
     * description.
     */
    private String deriveActionWithContext(String method, String path, String requestBody, String responseBody) {
        // Extract the resource name from the path
        String[] parts = path.split("/");
        if (parts.length < 2) {
            return method.toLowerCase();
        }

        // Find the main resource and sub-action
        String resource = null;
        String subAction = null;
        String resourceId = null;
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty() || "api".equals(part)) {
                continue;
            }
            if (resource == null && !isNumeric(part)) {
                resource = part;
            } else if (resource != null && isNumeric(part) && resourceId == null) {
                resourceId = part;
            } else if (resource != null && !isNumeric(part)) {
                subAction = part;
            }
        }

        if (resource == null) {
            return method.toLowerCase();
        }

        // Try to extract entity identifier from response body
        String entityRef = extractEntityReference(responseBody, requestBody, resource);

        // Build action description with entity context
        String baseAction = switch (method.toUpperCase()) {
            case "POST" -> subAction != null ? subAction.replace("-", " ") : "Create " + singularize(resource);
            case "PUT", "PATCH" -> subAction != null ? subAction.replace("-", " ") : "Update " + singularize(resource);
            case "DELETE" -> "Delete " + singularize(resource);
            default -> method.toLowerCase() + " " + resource;
        };

        // Capitalize the action
        baseAction = capitalize(baseAction);

        // Append entity reference if available
        if (entityRef != null && !entityRef.isBlank()) {
            return baseAction + ": " + entityRef;
        }

        return baseAction;
    }

    /**
     * Extract a meaningful entity reference from the response or request body.
     * Priority: serial/account_number > name > email > reference > id
     */
    private String extractEntityReference(String responseBody, String requestBody, String resource) {
        // Try response body first (usually contains the full entity)
        String ref = tryExtractReference(responseBody, resource);
        if (ref != null) {
            return ref;
        }

        // Fall back to request body
        return tryExtractReference(requestBody, resource);
    }

    @SuppressWarnings("unchecked")
    private String tryExtractReference(String jsonBody, String resource) {
        if (jsonBody == null || jsonBody.isBlank()) {
            return null;
        }

        try {
            Map<String, Object> body = objectMapper.readValue(jsonBody, new TypeReference<Map<String, Object>>() {
            });

            // If response has a "data" wrapper, unwrap it
            if (body.containsKey("data") && body.get("data") instanceof Map) {
                body = (Map<String, Object>) body.get("data");
            }

            // Resource-specific extraction
            switch (resource.toLowerCase()) {
                case "terminals" -> {
                    String serial = getStringField(body, "serial", "serial_number", "serialNumber");
                    if (serial != null)
                        return serial;
                }
                case "settlement-accounts" -> {
                    String accountName = getStringField(body, "account_name", "accountName", "name");
                    String accountNumber = getStringField(body, "account_number", "accountNumber");
                    if (accountName != null && accountNumber != null) {
                        return accountName + " (" + maskNumber(accountNumber) + ")";
                    }
                    if (accountName != null)
                        return accountName;
                    if (accountNumber != null)
                        return maskNumber(accountNumber);
                }
                case "branches" -> {
                    String name = getStringField(body, "name", "branch_name", "branchName");
                    if (name != null)
                        return "\"" + name + "\"";
                }
                case "roles" -> {
                    String name = getStringField(body, "name", "role_name", "roleName");
                    if (name != null)
                        return "\"" + name + "\"";
                }
                case "merchant-users", "invitations" -> {
                    String name = getStringField(body, "name", "full_name", "fullName");
                    String email = getStringField(body, "email");
                    String role = getStringField(body, "role", "role_name", "roleName");
                    StringBuilder ref = new StringBuilder();
                    if (name != null) {
                        ref.append(name);
                    } else if (email != null) {
                        ref.append(email);
                    }
                    if (role != null && !ref.isEmpty()) {
                        ref.append(" as ").append(role);
                    }
                    if (!ref.isEmpty())
                        return ref.toString();
                }
                case "disputes" -> {
                    String reference = getStringField(body, "reference", "dispute_reference", "disputeReference");
                    if (reference != null)
                        return reference;
                }
                case "notifications" -> {
                    // For mark-as-read, no specific reference needed
                    return null;
                }
                case "virtual-accounts" -> {
                    String accountNumber = getStringField(body, "account_number", "accountNumber");
                    if (accountNumber != null)
                        return accountNumber;
                }
            }

            // Generic fallback: try common fields
            String name = getStringField(body, "name");
            if (name != null)
                return name;

            String email = getStringField(body, "email");
            if (email != null)
                return email;

            String reference = getStringField(body, "reference");
            if (reference != null)
                return reference;

        } catch (Exception e) {
            log.debug("Failed to parse JSON for entity reference: {}", e.getMessage());
        }

        return null;
    }

    private String getStringField(Map<String, Object> map, String... fieldNames) {
        for (String fieldName : fieldNames) {
            Object value = map.get(fieldName);
            if (value != null && !value.toString().isBlank()) {
                return value.toString();
            }
        }
        return null;
    }

    /**
     * Mask a number for display (show last 4 digits).
     */
    private String maskNumber(String number) {
        if (number == null || number.length() <= 4) {
            return number;
        }
        return "****" + number.substring(number.length() - 4);
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

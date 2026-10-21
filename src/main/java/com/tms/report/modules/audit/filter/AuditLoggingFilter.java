package com.tms.report.modules.audit.filter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tms.report.core.security.MerchantUserDetails;
import com.tms.report.modules.audit.model.AuditLog;
import com.tms.report.modules.audit.repository.AuditLogRepository;
import com.tms.report.modules.audit.service.AuditEntityLookupService;
import com.tms.report.modules.audit.util.DescriptionBuilder;
import com.tms.report.modules.audit.util.EntityType;
import com.tms.report.modules.audit.util.PathContext;
import com.tms.report.modules.audit.util.PathContextExtractor;
import com.tms.report.modules.merchantuser.model.MerchantUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
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
    private final ApplicationContext applicationContext;

    public AuditLoggingFilter(AuditLogRepository auditLogRepository, ApplicationContext applicationContext) {
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = new ObjectMapper();
        this.applicationContext = applicationContext;
    }

    /**
     * Get the request-scoped AuditEntityLookupService bean from the application
     * context. This must be retrieved per-request because the service is
     * request-scoped.
     *
     * @return the AuditEntityLookupService for the current request
     */
    private AuditEntityLookupService getLookupService() {
        return applicationContext.getBean(AuditEntityLookupService.class);
    }

    /**
     * Extract the merchant ID from the current security context.
     *
     * @return the merchant ID or null if not authenticated or not a merchant user
     */
    private Long getMerchantIdFromContext() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof MerchantUserDetails details)) {
            return null;
        }
        MerchantUser user = details.getMerchantUser();
        return user != null ? user.getMerchantId() : null;
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

        // Derive action with smart entity context (uses lookup service for nested
        // resources)
        String action = deriveSmartAction(method, path, requestBody, responseBody, user.getMerchantId());

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
     * Derive a smart, human-readable action description using entity lookups for
     * nested resource operations. Falls back to the existing
     * deriveActionWithContext method on error.
     *
     * <p>
     * For nested resources like /branches/{id}/tids/{tidId}, this method resolves
     * entity names using the AuditEntityLookupService to produce descriptions like
     * "Assign TID 2001234 to branch 'Lagos Main'".
     *
     * @param method
     *            HTTP method (POST, PUT, PATCH, DELETE)
     * @param path
     *            URL path like "/branches/123/tids/456"
     * @param requestBody
     *            JSON request body (may be null)
     * @param responseBody
     *            JSON response body (may be null)
     * @param merchantId
     *            the merchant ID for entity lookups
     * @return human-readable action description
     */
    private String deriveSmartAction(String method, String path, String requestBody, String responseBody,
            Long merchantId) {
        try {
            // Check for special paths first (settlement-config/request-account)
            if (isSettlementAccountRequestPath(path, method)) {
                return buildSettlementAccountRequestDescription(requestBody, merchantId);
            }

            // Check for merchant user management paths
            String merchantUserDescription = buildMerchantUserDescription(method, path, requestBody, responseBody);
            if (merchantUserDescription != null) {
                return merchantUserDescription;
            }

            // Check for terminal action paths (lock, unlock, config updates)
            Optional<String> terminalAction = buildTerminalActionDescription(method, path);
            if (terminalAction.isPresent()) {
                return terminalAction.get();
            }

            // Check for nested resource operations first
            Optional<PathContext> nestedContext = PathContextExtractor.extractNestedContext(path);
            if (nestedContext.isPresent()) {
                PathContext ctx = nestedContext.get();

                if (ctx.isNestedResource()) {
                    // Nested resource operation (e.g., /branches/123/tids/456)
                    return buildSmartNestedDescription(method, ctx, merchantId);
                } else if (ctx.isSubAction()) {
                    // Sub-action operation (e.g., /branches/123/settlement-account)
                    return buildSmartSubActionDescription(method, ctx, requestBody, responseBody, merchantId);
                }
            }

            // Check for root resource operations (e.g., /branches/123)
            Optional<PathContext> rootContext = PathContextExtractor.extractRootContext(path);
            if (rootContext.isPresent()) {
                PathContext ctx = rootContext.get();
                return buildSmartRootDescription(method, ctx, requestBody, responseBody, merchantId);
            }

            // Fall back to existing method for unrecognized paths
            return deriveActionWithContext(method, path, requestBody, responseBody);

        } catch (Exception e) {
            log.debug("Failed to generate smart action description for path={}: {}", path, e.getMessage());
            // Fall back to existing method on any error
            return deriveActionWithContext(method, path, requestBody, responseBody);
        }
    }

    /**
     * Check if the path is a settlement account request path.
     *
     * @param path
     *            the URL path
     * @param method
     *            the HTTP method
     * @return true if this is a POST to /settlement-config/request-account
     */
    private boolean isSettlementAccountRequestPath(String path, String method) {
        if (!"POST".equalsIgnoreCase(method)) {
            return false;
        }
        // Normalize path: remove query string
        String normalizedPath = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        // Remove trailing slash
        if (normalizedPath.endsWith("/")) {
            normalizedPath = normalizedPath.substring(0, normalizedPath.length() - 1);
        }
        return normalizedPath.endsWith("/settlement-config/request-account");
    }

    /**
     * Pattern for terminal lock/unlock paths like /terminals/{id}/lock or
     * /terminals/{id}/unlock. Captures: group(1)=terminal ID, group(2)=action (lock
     * or unlock).
     */
    private static final Pattern TERMINAL_ACTION_PATTERN = Pattern.compile("/terminals/(\\d+)/(lock|unlock)(?:/)?$",
            Pattern.CASE_INSENSITIVE);

    /**
     * Pattern for root terminal paths like /terminals/{id}. Captures:
     * group(1)=terminal ID.
     */
    private static final Pattern TERMINAL_ROOT_PATTERN = Pattern.compile("/terminals/(\\d+)(?:/)?$",
            Pattern.CASE_INSENSITIVE);

    /**
     * Build a description for terminal action operations (lock, unlock, config
     * updates).
     *
     * <p>
     * Handles the following patterns:
     * <ul>
     * <li>POST /terminals/{id}/lock → "Lock terminal {serial}"</li>
     * <li>POST /terminals/{id}/unlock → "Unlock terminal {serial}"</li>
     * <li>PUT/PATCH /terminals/{id} → "Update terminal {serial} configuration"</li>
     * </ul>
     *
     * @param method
     *            HTTP method
     * @param path
     *            URL path
     * @return Optional containing the description if this is a terminal action
     *         path, empty otherwise
     */
    private Optional<String> buildTerminalActionDescription(String method, String path) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }

        // Normalize path: remove query string and trailing slashes
        String normalizedPath = normalizeTerminalPath(path);

        // Check for terminal lock/unlock actions (POST /terminals/{id}/lock or /unlock)
        if ("POST".equalsIgnoreCase(method)) {
            Matcher actionMatcher = TERMINAL_ACTION_PATTERN.matcher(normalizedPath);
            if (actionMatcher.find()) {
                String terminalIdStr = actionMatcher.group(1);
                String action = actionMatcher.group(2).toLowerCase();

                Long terminalId = parseNumericId(terminalIdStr);
                if (terminalId == null) {
                    return Optional.empty();
                }

                // Look up terminal serial
                String serial = resolveTerminalSerial(terminalId);

                // Build description based on action
                String actionVerb = "lock".equals(action) ? "Lock" : "Unlock";
                if (serial != null) {
                    return Optional.of(String.format("%s terminal %s", actionVerb, serial));
                }

                // Fallback with ID if serial lookup fails
                return Optional.of(String.format("%s terminal #%d", actionVerb, terminalId));
            }
        }

        // Check for terminal configuration updates (PUT/PATCH /terminals/{id})
        if ("PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method)) {
            Matcher rootMatcher = TERMINAL_ROOT_PATTERN.matcher(normalizedPath);
            if (rootMatcher.find()) {
                String terminalIdStr = rootMatcher.group(1);
                Long terminalId = parseNumericId(terminalIdStr);
                if (terminalId == null) {
                    return Optional.empty();
                }

                // Look up terminal serial
                String serial = resolveTerminalSerial(terminalId);

                if (serial != null) {
                    return Optional.of(String.format("Update terminal %s configuration", serial));
                }

                // Fallback with ID if serial lookup fails
                return Optional.of(String.format("Update terminal #%d configuration", terminalId));
            }
        }

        return Optional.empty();
    }

    /**
     * Resolve terminal serial number from terminal ID using the lookup service.
     *
     * @param terminalId
     *            the terminal ID
     * @return terminal serial number or null if lookup fails
     */
    private String resolveTerminalSerial(Long terminalId) {
        try {
            return getLookupService().getTerminalSerial(terminalId).orElse(null);
        } catch (Exception e) {
            log.debug("Failed to resolve terminal serial for ID {}: {}", terminalId, e.getMessage());
            return null;
        }
    }

    /**
     * Parse a string as a numeric ID.
     *
     * @param idStr
     *            the string to parse
     * @return the parsed Long, or null if parsing fails
     */
    private Long parseNumericId(String idStr) {
        if (idStr == null || idStr.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(idStr);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Normalize a URL path by removing query strings and trailing slashes.
     *
     * @param path
     *            the path to normalize
     * @return normalized path
     */
    private String normalizeTerminalPath(String path) {
        // Remove query string if present
        int queryIndex = path.indexOf('?');
        String result = queryIndex >= 0 ? path.substring(0, queryIndex) : path;

        // Remove trailing slash (but keep leading slash)
        if (result.length() > 1 && result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }

        return result;
    }

    /**
     * Build a description for settlement account request operations.
     *
     * <p>
     * Format when current account exists: "Request settlement account change from
     * '{current_bank_name} ****{current_last_4}' to '{new_bank_name}
     * ****{new_last_4} ({new_account_name})'"
     *
     * <p>
     * Format when no current account: "Request settlement account '{new_bank_name}
     * ****{new_last_4} ({new_account_name})'"
     *
     * @param requestBody
     *            the JSON request body containing new account details
     * @param merchantId
     *            the merchant ID for looking up current default account
     * @return human-readable description for the audit log
     */
    private String buildSettlementAccountRequestDescription(String requestBody, Long merchantId) {
        AuditEntityLookupService lookupService = getLookupService();

        // Get current default settlement account
        var currentAccount = lookupService.getDefaultSettlementAccount(merchantId).orElse(null);

        // Extract new account details from request body
        var newAccount = lookupService.extractSettlementAccountFromJson(requestBody).orElse(null);

        // Use DescriptionBuilder to generate the description
        return DescriptionBuilder.buildSettlementRequestDescription(currentAccount, newAccount);
    }

    /**
     * Build a description for merchant user management actions.
     *
     * <p>
     * Handles the following cases:
     * <ul>
     * <li>POST /merchant-users/invite → "Invite user {email} as {role}"</li>
     * <li>PUT/PATCH /merchant-users/{id} with role change → "Update user {name}:
     * role changed to {new_role}"</li>
     * <li>POST/PUT /merchant-users/{id}/deactivate → "Deactivate user {name}"</li>
     * </ul>
     *
     * @param method
     *            HTTP method
     * @param path
     *            URL path
     * @param requestBody
     *            JSON request body
     * @param responseBody
     *            JSON response body
     * @return human-readable description, or null if path is not a merchant user
     *         path
     */
    @SuppressWarnings("unchecked")
    private String buildMerchantUserDescription(String method, String path, String requestBody, String responseBody) {
        if (path == null || path.isBlank()) {
            return null;
        }

        // Normalize path: remove query string and trailing slash
        String normalizedPath = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        if (normalizedPath.endsWith("/")) {
            normalizedPath = normalizedPath.substring(0, normalizedPath.length() - 1);
        }

        // Check for user invite: POST /merchant-users/invite
        if ("POST".equalsIgnoreCase(method) && normalizedPath.endsWith("/merchant-users/invite")) {
            return buildUserInviteDescription(responseBody, requestBody);
        }

        // Check for user deactivation: POST/PUT /merchant-users/{id}/deactivate
        if (("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method))
                && normalizedPath.matches(".*/merchant-users/\\d+/deactivate$")) {
            return buildUserDeactivateDescription(responseBody, requestBody);
        }

        // Check for user update: PUT/PATCH /merchant-users/{id}
        if (("PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method))
                && normalizedPath.matches(".*/merchant-users/\\d+$")) {
            return buildUserUpdateDescription(responseBody, requestBody);
        }

        // Not a merchant user path we handle
        return null;
    }

    /**
     * Build description for user invite action. Format: "Invite user {email} as
     * {role}"
     *
     * @param responseBody
     *            JSON response body
     * @param requestBody
     *            JSON request body (fallback)
     * @return human-readable description
     */
    @SuppressWarnings("unchecked")
    private String buildUserInviteDescription(String responseBody, String requestBody) {
        // Try response body first (priority per Requirement 4.4)
        Map<String, Object> data = extractDataFromBody(responseBody);
        if (data == null || data.isEmpty()) {
            data = extractDataFromBody(requestBody);
        }

        if (data == null || data.isEmpty()) {
            return "Invite user";
        }

        String email = getStringField(data, "email");
        String role = getStringField(data, "role", "role_name", "roleName");

        if (email != null && role != null) {
            return String.format("Invite user %s as %s", email, role);
        } else if (email != null) {
            return String.format("Invite user %s", email);
        }

        return "Invite user";
    }

    /**
     * Build description for user deactivation action. Format: "Deactivate user
     * {name}"
     *
     * @param responseBody
     *            JSON response body
     * @param requestBody
     *            JSON request body (fallback)
     * @return human-readable description
     */
    @SuppressWarnings("unchecked")
    private String buildUserDeactivateDescription(String responseBody, String requestBody) {
        // Try response body first (priority per Requirement 4.4)
        Map<String, Object> data = extractDataFromBody(responseBody);
        if (data == null || data.isEmpty()) {
            data = extractDataFromBody(requestBody);
        }

        if (data == null || data.isEmpty()) {
            return "Deactivate user";
        }

        String name = getStringField(data, "name", "full_name", "fullName");
        if (name == null) {
            name = getStringField(data, "email");
        }

        if (name != null) {
            return String.format("Deactivate user %s", name);
        }

        return "Deactivate user";
    }

    /**
     * Build description for user update action. Detects role changes and formats
     * accordingly. Format: "Update user {name}: role changed to {new_role}" for
     * role changes Format: "Update user {name}" for other updates
     *
     * @param responseBody
     *            JSON response body
     * @param requestBody
     *            JSON request body (fallback)
     * @return human-readable description
     */
    @SuppressWarnings("unchecked")
    private String buildUserUpdateDescription(String responseBody, String requestBody) {
        // Try response body first (priority per Requirement 4.4)
        Map<String, Object> data = extractDataFromBody(responseBody);
        if (data == null || data.isEmpty()) {
            data = extractDataFromBody(requestBody);
        }

        if (data == null || data.isEmpty()) {
            return "Update user";
        }

        String name = getStringField(data, "name", "full_name", "fullName");
        if (name == null) {
            name = getStringField(data, "email");
        }

        String role = getStringField(data, "role", "role_name", "roleName");

        // Check if this is a role change by looking at request body for role field
        Map<String, Object> requestData = extractDataFromBody(requestBody);
        boolean isRoleChange = requestData != null && (requestData.containsKey("role")
                || requestData.containsKey("role_name") || requestData.containsKey("roleName"));

        if (name != null && role != null && isRoleChange) {
            return String.format("Update user %s: role changed to %s", name, role);
        } else if (name != null) {
            return String.format("Update user %s", name);
        }

        return "Update user";
    }

    /**
     * Extract the 'data' object from a JSON body. Handles both wrapped responses
     * {data: {...}} and direct objects.
     *
     * @param jsonBody
     *            the JSON body to parse
     * @return the data map, or null if parsing fails
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> extractDataFromBody(String jsonBody) {
        if (jsonBody == null || jsonBody.isBlank()) {
            return null;
        }

        try {
            Map<String, Object> body = objectMapper.readValue(jsonBody, new TypeReference<Map<String, Object>>() {
            });

            // If response has a "data" wrapper, unwrap it
            if (body.containsKey("data") && body.get("data") instanceof Map) {
                return (Map<String, Object>) body.get("data");
            }

            return body;
        } catch (Exception e) {
            log.debug("Failed to extract data from body: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Build a smart description for nested resource operations using entity
     * lookups.
     *
     * @param method
     *            HTTP method (POST, DELETE)
     * @param ctx
     *            path context with parent and target entity info
     * @param merchantId
     *            the merchant ID for entity lookups
     * @return human-readable description like "Assign TID 2001234 to branch 'Lagos
     *         Main'"
     */
    private String buildSmartNestedDescription(String method, PathContext ctx, Long merchantId) {
        AuditEntityLookupService lookupService = getLookupService();

        // Resolve parent entity name
        String parentName = resolveEntityName(ctx.parentType(), ctx.parentId(), merchantId, lookupService);

        // Resolve target entity name/identifier
        String targetName = resolveEntityName(ctx.targetType(), ctx.targetId(), merchantId, lookupService);

        // If we have both names, use the description builder
        if (parentName != null && targetName != null) {
            return DescriptionBuilder.buildNestedDescription(method, parentName, ctx.parentType(), targetName,
                    ctx.targetType());
        }

        // Fall back to ID-based description
        return DescriptionBuilder.buildFallbackDescription(method, ctx.parentId(), ctx.parentType(), ctx.targetId(),
                ctx.targetType());
    }

    /**
     * Build a smart description for sub-action operations (e.g.,
     * /branches/123/settlement-account).
     *
     * @param method
     *            HTTP method
     * @param ctx
     *            path context with parent entity and sub-action
     * @param requestBody
     *            JSON request body
     * @param responseBody
     *            JSON response body
     * @param merchantId
     *            the merchant ID for entity lookups
     * @return human-readable description
     */
    private String buildSmartSubActionDescription(String method, PathContext ctx, String requestBody,
            String responseBody, Long merchantId) {
        AuditEntityLookupService lookupService = getLookupService();

        // Resolve parent entity name
        String parentName = resolveEntityName(ctx.parentType(), ctx.parentId(), merchantId, lookupService);

        String subAction = ctx.subAction();
        if (subAction == null) {
            subAction = "action";
        }

        // Handle settlement-account sub-action specially
        if ("settlement-account".equals(subAction)) {
            return buildSettlementAccountSubActionDescription(method, parentName, ctx, requestBody, lookupService);
        }

        // Generic sub-action description
        String action = switch (method.toUpperCase()) {
            case "POST" -> "Assign";
            case "PUT", "PATCH" -> "Update";
            case "DELETE" -> "Clear";
            default -> method;
        };

        String formattedSubAction = subAction.replace("-", " ");
        String parentDesc = parentName != null
                ? ctx.parentType().getSingular() + " '" + parentName + "'"
                : ctx.parentType().getSingular() + " #" + ctx.parentId();

        if ("DELETE".equalsIgnoreCase(method)) {
            return String.format("Clear %s from %s", formattedSubAction, parentDesc);
        }

        return String.format("%s %s for %s", action, formattedSubAction, parentDesc);
    }

    /**
     * Build description for settlement-account sub-action operations.
     *
     * <p>
     * Format for PUT: "Assign settlement account '{bank} ****{last4}' to branch
     * '{name}'" Format for DELETE: "Clear settlement account from branch '{name}'"
     *
     * @param method
     *            HTTP method (PUT/PATCH for assign, DELETE for clear)
     * @param parentName
     *            the resolved parent entity name (e.g., branch name)
     * @param ctx
     *            path context with parent entity info
     * @param requestBody
     *            JSON request body (may contain settlement account details)
     * @param lookupService
     *            the lookup service to use
     * @return human-readable description
     */
    private String buildSettlementAccountSubActionDescription(String method, String parentName, PathContext ctx,
            String requestBody, AuditEntityLookupService lookupService) {
        String parentDesc = parentName != null
                ? ctx.parentType().getSingular() + " '" + parentName + "'"
                : ctx.parentType().getSingular() + " #" + ctx.parentId();

        if ("DELETE".equalsIgnoreCase(method)) {
            return String.format("Clear settlement account from %s", parentDesc);
        }

        // For PUT/PATCH, try to extract settlement account details from request body
        var accountInfo = lookupService.extractSettlementAccountFromJson(requestBody).orElse(null);

        if (accountInfo != null) {
            String accountDesc = accountInfo.bankName() + " " + accountInfo.maskedAccountNumber();
            return String.format("Assign settlement account '%s' to %s", accountDesc, parentDesc);
        }

        // Fallback if account details can't be extracted
        return String.format("Assign settlement account to %s", parentDesc);
    }

    /**
     * Build a smart description for root resource operations (e.g., /branches/123).
     *
     * @param method
     *            HTTP method
     * @param ctx
     *            path context with entity type and ID
     * @param requestBody
     *            JSON request body
     * @param responseBody
     *            JSON response body
     * @param merchantId
     *            the merchant ID for entity lookups
     * @return human-readable description
     */
    private String buildSmartRootDescription(String method, PathContext ctx, String requestBody, String responseBody,
            Long merchantId) {
        AuditEntityLookupService lookupService = getLookupService();

        // Try to get entity name from response body first (priority per Requirement
        // 4.4)
        String entityName = extractEntityNameFromBody(responseBody, ctx.targetType());
        if (entityName == null) {
            entityName = extractEntityNameFromBody(requestBody, ctx.targetType());
        }

        // If not in body, try lookup service
        if (entityName == null) {
            entityName = resolveEntityName(ctx.targetType(), ctx.targetId(), merchantId, lookupService);
        }

        // Build description with resolved name or fallback to ID
        if (entityName != null) {
            return DescriptionBuilder.buildRootDescription(method, entityName, ctx.targetType());
        }

        // Fallback with ID
        String entityDesc = "#" + ctx.targetId();
        return DescriptionBuilder.buildRootDescription(method, entityDesc, ctx.targetType());
    }

    /**
     * Resolve an entity name by type and ID using the lookup service.
     *
     * @param entityType
     *            the type of entity to resolve
     * @param entityId
     *            the entity ID
     * @param merchantId
     *            the merchant ID for scoped lookups
     * @param lookupService
     *            the lookup service to use
     * @return resolved entity name or null if not found
     */
    private String resolveEntityName(EntityType entityType, Long entityId, Long merchantId,
            AuditEntityLookupService lookupService) {
        if (entityType == null || entityId == null) {
            return null;
        }

        return switch (entityType) {
            case BRANCH -> lookupService.getBranchName(entityId, merchantId).orElse(null);
            case TID -> lookupService.getTidValue(entityId).orElse(null);
            case TERMINAL -> lookupService.getTerminalSerial(entityId).orElse(null);
            case SETTLEMENT_ACCOUNT -> lookupService.getSettlementAccount(entityId, merchantId)
                    .map(info -> info.bankName() + " " + info.maskedAccountNumber()).orElse(null);
            default -> null;
        };
    }

    /**
     * Extract entity name from JSON body based on entity type.
     *
     * @param jsonBody
     *            the JSON body to parse
     * @param entityType
     *            the expected entity type
     * @return extracted entity name or null
     */
    @SuppressWarnings("unchecked")
    private String extractEntityNameFromBody(String jsonBody, EntityType entityType) {
        if (jsonBody == null || jsonBody.isBlank() || entityType == null) {
            return null;
        }

        try {
            Map<String, Object> body = objectMapper.readValue(jsonBody, new TypeReference<Map<String, Object>>() {
            });

            // If response has a "data" wrapper, unwrap it
            if (body.containsKey("data") && body.get("data") instanceof Map) {
                body = (Map<String, Object>) body.get("data");
            }

            return switch (entityType) {
                case BRANCH -> getStringField(body, "name", "branch_name", "branchName");
                case TERMINAL -> getStringField(body, "serial", "serial_number", "serialNumber");
                case TID -> getStringField(body, "terminal_id", "terminalId");
                case SETTLEMENT_ACCOUNT -> {
                    String accountName = getStringField(body, "account_name", "accountName");
                    String bankName = getStringField(body, "bank_name", "bankName");
                    if (accountName != null && bankName != null) {
                        yield bankName + " - " + accountName;
                    }
                    yield accountName != null ? accountName : bankName;
                }
                case MERCHANT_USER -> getStringField(body, "name", "full_name", "fullName", "email");
                case ROLE -> getStringField(body, "name", "role_name", "roleName");
                default -> getStringField(body, "name");
            };
        } catch (Exception e) {
            log.debug("Failed to extract entity name from body: {}", e.getMessage());
            return null;
        }
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

        // Determine the effective entity type for extraction
        // For nested resources (e.g., /branches/{id}/terminals/{terminalId}),
        // the subAction represents the actual entity being operated on
        String effectiveResource = isEntityResource(subAction) ? subAction : resource;

        // Try to extract entity identifier from response body
        String entityRef = extractEntityReference(responseBody, requestBody, effectiveResource);

        // Build action description with entity context
        String baseAction = switch (method.toUpperCase()) {
            case "POST" -> subAction != null
                    ? buildNestedAction("Assign", subAction, resource)
                    : "Create " + singularize(resource);
            case "PUT", "PATCH" -> subAction != null ? subAction.replace("-", " ") : "Update " + singularize(resource);
            case "DELETE" -> subAction != null
                    ? buildNestedAction("Remove", subAction, resource)
                    : "Delete " + singularize(resource);
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

    /**
     * Check if a path segment represents an entity resource that can be extracted.
     */
    private boolean isEntityResource(String segment) {
        if (segment == null) {
            return false;
        }
        return switch (segment.toLowerCase()) {
            case "terminals", "settlement-accounts", "branches", "roles", "merchant-users", "invitations", "disputes",
                    "virtual-accounts" ->
                true;
            default -> false;
        };
    }

    /**
     * Build a descriptive action for nested resource operations. E.g., "Assign
     * terminal to branch" for POST /branches/{id}/terminals/{terminalId}
     */
    private String buildNestedAction(String verb, String nestedResource, String parentResource) {
        return verb + " " + singularize(nestedResource) + " to " + singularize(parentResource);
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
        if (word == null || word.isEmpty()) {
            return word;
        }
        // Handle -ies -> -y (e.g., "categories" -> "category")
        if (word.endsWith("ies")) {
            return word.substring(0, word.length() - 3) + "y";
        }
        // Handle -ches, -shes, -xes -> -ch, -sh, -x (e.g., "branches" -> "branch")
        if (word.endsWith("ches") || word.endsWith("shes") || word.endsWith("xes")) {
            return word.substring(0, word.length() - 2);
        }
        // Handle -es -> "" for other -es words (e.g., "boxes" -> "box")
        if (word.endsWith("es") && word.length() > 2) {
            char beforeEs = word.charAt(word.length() - 3);
            // Only remove -es if preceded by s, x, z, ch, sh
            if (beforeEs == 's' || beforeEs == 'x' || beforeEs == 'z') {
                return word.substring(0, word.length() - 2);
            }
        }
        // Handle regular -s -> "" (e.g., "terminals" -> "terminal")
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

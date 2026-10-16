package com.tms.report.modules.audit.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.tms.report.modules.audit.model.AuditLog;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for audit log entries. Maps internal AuditLog entity fields to
 * the frontend-expected format.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLogResponse {

    private Long id;

    @JsonProperty("admin_name")
    private String adminName;

    private AdminInfo admin;

    @JsonProperty("admin_role")
    private String adminRole;

    private String action;

    private String description;

    @JsonProperty("actionable_type")
    private String actionableType;

    @JsonProperty("actionable_id")
    private Long actionableId;

    @JsonProperty("ip_address")
    private String ipAddress;

    @JsonProperty("user_agent")
    private String userAgent;

    @JsonProperty("created_at")
    private LocalDateTime createdAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AdminInfo {
        private Long id;
        private String name;
        private String email;
    }

    /**
     * Convert an AuditLog entity to the response DTO.
     */
    public static AuditLogResponse from(AuditLog log) {
        return AuditLogResponse.builder().id(log.getId()).adminName(log.getUserName())
                .admin(AdminInfo.builder().id(log.getUserId()).name(log.getUserName()).email(log.getUserEmail())
                        .build())
                .adminRole(log.getUserRole()).action(deriveSimpleActionLabel(log)).description(buildDescription(log))
                .actionableType(extractActionableType(log.getPath())).actionableId(extractActionableId(log.getPath()))
                .ipAddress(log.getIpAddress()).userAgent(log.getUserAgent()).createdAt(log.getCreatedAt()).build();
    }

    /**
     * Derive a simple action label from the path and method. Examples: "Terminal
     * assignment", "TID removal", "Branch update"
     */
    private static String deriveSimpleActionLabel(AuditLog log) {
        String path = log.getPath();
        String method = log.getMethod();

        if (path == null || method == null) {
            return log.getAction();
        }

        // Normalize path
        String normalizedPath = path.toLowerCase();

        // ── Notifications ──
        if (normalizedPath.contains("/notifications/mark-all-as-read")) {
            return "Mark all notifications read";
        }
        if (normalizedPath.matches(".*/notifications/\\d+/mark-as-read.*")) {
            return "Mark notification read";
        }

        // ── Disputes ──
        if (normalizedPath.matches(".*/disputes/\\d+/mark-read.*")) {
            return "Mark dispute read";
        }

        // ── Merchant Settings ──
        if (normalizedPath.contains("/merchant-settings/receipt-reprint-guard")) {
            return "Receipt reprint guard toggle";
        }

        // ── Roles and Privileges ──
        if (normalizedPath.contains("/roles/assign")) {
            return "Role assignment";
        }
        if (normalizedPath.contains("/roles/unassign")) {
            return "Role unassignment";
        }
        if (normalizedPath.matches(".*/roles/\\d+$") && "PUT".equalsIgnoreCase(method)) {
            return "Role update";
        }
        if (normalizedPath.matches(".*/roles/\\d+$") && "DELETE".equalsIgnoreCase(method)) {
            return "Role deletion";
        }
        if (normalizedPath.endsWith("/roles") && "POST".equalsIgnoreCase(method)) {
            return "Role creation";
        }

        // ── Settlement account request ──
        if (normalizedPath.contains("/settlement-config/request-account")) {
            return "Settlement account request";
        }

        // ── Terminal operations ──
        if (normalizedPath.matches(".*/terminals/\\d+/request-prep.*")) {
            return "Terminal key injection request";
        }
        if (normalizedPath.matches(".*/terminals/\\d+/lock.*")) {
            return "Terminal lock";
        }
        if (normalizedPath.matches(".*/terminals/\\d+/unlock.*")) {
            return "Terminal unlock";
        }

        // ── Merchant user actions ──
        if (normalizedPath.contains("/merchant-users/invite")) {
            return "User invitation";
        }
        if (normalizedPath.matches(".*/merchant-users/\\d+/deactivate.*")) {
            return "User deactivation";
        }
        if (normalizedPath.matches(".*/merchant-users/\\d+/activate.*")) {
            return "User activation";
        }
        if (normalizedPath.matches(".*/merchant-users/\\d+$")
                && ("PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method))) {
            return "User update";
        }

        // ── Nested resources: /branches/{id}/tids/{tidId} ──
        if (normalizedPath.matches(".*/branches/\\d+/tids/\\d+.*")) {
            return "POST".equalsIgnoreCase(method) ? "TID assignment" : "TID removal";
        }
        // ── Nested resources: /branches/{id}/terminals/{terminalId} ──
        if (normalizedPath.matches(".*/branches/\\d+/terminals/\\d+.*")) {
            return "POST".equalsIgnoreCase(method) ? "Terminal assignment" : "Terminal removal";
        }

        // ── Branch settlement account ──
        if (normalizedPath.matches(".*/branches/\\d+/settlement-account.*")) {
            return "DELETE".equalsIgnoreCase(method) ? "Settlement account removal" : "Settlement account assignment";
        }

        // ── Branch activation/deactivation ──
        if (normalizedPath.matches(".*/branches/\\d+/activate.*")) {
            return "Branch activation";
        }
        if (normalizedPath.matches(".*/branches/\\d+/deactivate.*")) {
            return "Branch deactivation";
        }

        // ── Root terminal operations ──
        if (normalizedPath.matches(".*/terminals/\\d+$")) {
            return "Terminal configuration update";
        }

        // ── Root branch operations ──
        if (normalizedPath.matches(".*/branches/\\d+$") || normalizedPath.endsWith("/branches")) {
            return switch (method.toUpperCase()) {
                case "POST" -> "Branch creation";
                case "PUT", "PATCH" -> "Branch update";
                case "DELETE" -> "Branch deletion";
                default -> "Branch operation";
            };
        }

        // Fallback: use the stored action if it's short enough
        if (log.getAction() != null && log.getAction().length() <= 40) {
            return log.getAction();
        }

        // Generic fallback based on method and resource type
        String resourceType = extractActionableType(path);
        if (resourceType != null) {
            return switch (method.toUpperCase()) {
                case "POST" -> resourceType + " creation";
                case "PUT", "PATCH" -> resourceType + " update";
                case "DELETE" -> resourceType + " deletion";
                default -> resourceType + " operation";
            };
        }

        return log.getAction();
    }

    /**
     * Build a human-readable description from the action and merchant name. Format:
     * "{merchant_name} {action_details}"
     */
    private static String buildDescription(AuditLog log) {
        String merchantName = log.getUserName();
        String action = log.getAction();

        if (action == null) {
            action = log.getMethod() + " " + log.getPath();
        }

        // Build description with merchant name prefix
        if (merchantName != null && !merchantName.isBlank()) {
            return merchantName + " " + action.toLowerCase();
        }

        return action;
    }

    private static String extractActionableType(String path) {
        if (path == null)
            return null;
        // Extract resource type from path like /terminals/1/lock -> "Terminal"
        String[] parts = path.split("/");
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            if (!part.isEmpty() && !isNumeric(part) && !"api".equals(part)) {
                return capitalize(singularize(part));
            }
        }
        return null;
    }

    private static Long extractActionableId(String path) {
        if (path == null)
            return null;
        // Extract ID from path like /terminals/1/lock -> 1
        String[] parts = path.split("/");
        for (String part : parts) {
            if (isNumeric(part)) {
                return Long.parseLong(part);
            }
        }
        return null;
    }

    private static boolean isNumeric(String str) {
        try {
            Long.parseLong(str);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String singularize(String word) {
        if (word.endsWith("ies")) {
            return word.substring(0, word.length() - 3) + "y";
        }
        if (word.endsWith("s") && !word.endsWith("ss")) {
            return word.substring(0, word.length() - 1);
        }
        return word;
    }

    private static String capitalize(String str) {
        if (str == null || str.isEmpty())
            return str;
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }
}

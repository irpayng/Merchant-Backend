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
                .adminRole(log.getUserRole()).action(log.getAction()).description(buildDescription(log))
                .actionableType(extractActionableType(log.getPath())).actionableId(extractActionableId(log.getPath()))
                .ipAddress(log.getIpAddress()).userAgent(log.getUserAgent()).createdAt(log.getCreatedAt()).build();
    }

    private static String buildDescription(AuditLog log) {
        // Build a human-readable description from the action and path
        String action = log.getAction() != null ? log.getAction() : log.getMethod() + " " + log.getPath();
        return action + " (HTTP " + log.getResponseStatus() + ")";
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

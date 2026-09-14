package com.tms.report.modules.audit.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/**
 * Audit log entry for merchant dashboard actions. Captures all non-GET requests
 * made by merchants and their operator users.
 */
@Entity
@Table(name = "audit_logs", schema = "merchant", indexes = {
        @Index(name = "idx_audit_logs_merchant_id", columnList = "merchant_id"),
        @Index(name = "idx_audit_logs_user_id", columnList = "user_id"),
        @Index(name = "idx_audit_logs_created_at", columnList = "created_at")})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The merchant (business) this action belongs to — users.id. */
    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    /** The merchant_users.id who performed the action. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** User's name at the time of the action. */
    @Column(name = "user_name")
    private String userName;

    /** User's email at the time of the action. */
    @Column(name = "user_email")
    private String userEmail;

    /** User's role (owner, cashier, manager, etc.). */
    @Column(name = "user_role")
    private String userRole;

    /** HTTP method (POST, PUT, PATCH, DELETE). */
    @Column(nullable = false, length = 10)
    private String method;

    /** Request path (e.g. /terminals/1/lock). */
    @Column(nullable = false, length = 500)
    private String path;

    /** Human-readable action description derived from the endpoint. */
    @Column(length = 255)
    private String action;

    /** Request body (sanitized, excludes sensitive fields). */
    @Column(name = "request_body", columnDefinition = "TEXT")
    private String requestBody;

    /** HTTP response status code. */
    @Column(name = "response_status")
    private Integer responseStatus;

    /** Client IP address. */
    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    /** Client user agent. */
    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    // ── Transient fields for API response ───────────────────

    /**
     * User object for frontend compatibility. Built from
     * userName/userEmail/userRole.
     */
    @Transient
    @JsonProperty("user")
    public UserInfo getUser() {
        return new UserInfo(userId, userName, userEmail, userRole);
    }

    /** Simple DTO for user info in API response. */
    public record UserInfo(Long id, String name, String email, String role) {
    }
}

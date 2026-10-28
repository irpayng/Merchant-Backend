package com.tms.report.modules.session.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/**
 * Tracks active dashboard sessions for revocation capability. Each login
 * creates a session record; the JWT is validated against this table to ensure
 * the session hasn't been revoked.
 *
 * <p>
 * This enables global session revocation when:
 * <ul>
 * <li>User is blocked in tms-user</li>
 * <li>Password is changed/reset</li>
 * <li>Device is reset (admin action)</li>
 * <li>Merchant is decommissioned</li>
 * </ul>
 *
 * <p>
 * The token itself is not stored — only its SHA256 hash — so a database breach
 * doesn't expose valid tokens.
 */
@Entity
@Table(name = "sessions", schema = "merchant", indexes = {
        @Index(name = "idx_sessions_token_hash", columnList = "token_hash", unique = true),
        @Index(name = "idx_sessions_tms_user_id", columnList = "tms_user_id"),
        @Index(name = "idx_sessions_merchant_user_id", columnList = "merchant_user_id"),
        @Index(name = "idx_sessions_expires_at", columnList = "expires_at")})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Session {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The MerchantUser this session belongs to. */
    @Column(name = "merchant_user_id", nullable = false)
    private Long merchantUserId;

    /**
     * The tms-user ID (users.id for owners, operators.id for staff). Used to revoke
     * sessions when tms-user triggers a revocation event.
     */
    @Column(name = "tms_user_id", nullable = false)
    private Long tmsUserId;

    /**
     * Whether this is an owner (user) or staff (operator) session. Determines which
     * tms-user table the tmsUserId refers to.
     */
    @Column(name = "user_type", length = 20)
    @Builder.Default
    private String userType = "user";

    /** SHA256 hash of the JWT. Used for lookup on validation. */
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    /** Session channel — always "dashboard" for now. */
    @Column(name = "channel", length = 20)
    @Builder.Default
    private String channel = "dashboard";

    /** User-Agent or device identifier for audit/debugging. */
    @Column(name = "device_info", length = 500)
    private String deviceInfo;

    /** Client IP address at login time. */
    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** When the JWT expires (copied from token for efficient cleanup). */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /** Set when the session is revoked; null = active. */
    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    /** Reason for revocation (blocked, password_changed, etc.). */
    @Column(name = "revocation_reason", length = 50)
    private String revocationReason;

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired() {
        return expiresAt != null && expiresAt.isBefore(LocalDateTime.now());
    }

    public boolean isActive() {
        return !isRevoked() && !isExpired();
    }
}

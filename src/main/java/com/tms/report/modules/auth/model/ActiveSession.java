package com.tms.report.modules.auth.model;

import com.tms.report.modules.merchantuser.model.MerchantUser;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/**
 * Tracks active login sessions for merchant dashboard users. Each user can only
 * have one active session at a time — logging in on a new device invalidates
 * any existing session.
 */
@Entity
@Table(name = "active_sessions", schema = "merchant", indexes = {
        @Index(name = "idx_active_sessions_user", columnList = "merchant_user_id"),
        @Index(name = "idx_active_sessions_session_id", columnList = "session_id"),
        @Index(name = "idx_active_sessions_expires", columnList = "expires_at")})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActiveSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "merchant_user_id", nullable = false)
    private MerchantUser merchantUser;

    /** Unique session identifier embedded in the JWT token. */
    @Column(name = "session_id", nullable = false, unique = true, length = 64)
    private String sessionId;

    /** User-Agent or device description for display purposes. */
    @Column(name = "device_info", length = 500)
    private String deviceInfo;

    /** IP address of the client that initiated the session. */
    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** When this session expires (mirrors JWT expiration). */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;
}

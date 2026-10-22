package com.tms.report.modules.auth.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/**
 * Email verification token for payment gateway merchants. PG merchants use a
 * separate dashboard and don't have MerchantUser records, so their email
 * verification tokens are stored independently.
 */
@Entity
@Table(name = "pg_merchant_email_tokens", schema = "merchant", indexes = {
        @Index(name = "idx_pg_email_tokens_email", columnList = "email"),
        @Index(name = "idx_pg_email_tokens_user_id", columnList = "user_id")})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PgMerchantEmailToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The tms-user users.id of the PG merchant. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Email address to verify. */
    @Column(nullable = false)
    private String email;

    /** 6-digit OTP code. */
    @Column(nullable = false, length = 6)
    private String otp;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    public boolean isExpired() {
        return expiresAt != null && expiresAt.isBefore(LocalDateTime.now());
    }

    public boolean isVerified() {
        return verifiedAt != null;
    }

    public boolean isUsable() {
        return !isExpired() && !isVerified();
    }
}

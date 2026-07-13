package com.tms.report.modules.merchantuser.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/**
 * A one-time account-activation / password-setup challenge for a
 * {@link MerchantUser}. Supports both channels the product requires: a
 * {@code link} token emailed to the merchant, or an {@code otp} code sent by
 * SMS/email. Consumed once the password is set.
 */
@Entity
@Table(name = "activation_tokens", schema = "merchant")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActivationToken {

    public static final String CHANNEL_LINK = "link";
    public static final String CHANNEL_OTP = "otp";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_user_id", nullable = false)
    private Long merchantUserId;

    /** Opaque token for the emailed activation link (null for otp challenges). */
    private String token;

    /**
     * Short numeric code for the SMS/email OTP challenge (null for link
     * challenges).
     */
    private String otp;

    private String channel;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    public boolean isExpired() {
        return expiresAt != null && expiresAt.isBefore(LocalDateTime.now());
    }

    public boolean isConsumed() {
        return consumedAt != null;
    }

    public boolean isUsable() {
        return !isExpired() && !isConsumed();
    }
}

package com.tms.report.modules.merchantuser.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * A login for the merchant dashboard. Two roles:
 * <ul>
 * <li><b>owner</b> — the business owner; full read of their own transactions,
 * terminals, settlements/statements, audit; can invite/revoke cashiers.</li>
 * <li><b>cashier</b> — delegated, view-only; optionally locked to a single
 * {@code terminalId}.</li>
 * </ul>
 *
 * <p>
 * Bound to a merchant via {@code merchantId} (the onboarded merchant's
 * {@code users.id}). Onboarding is done by document upload which captures no
 * password, so accounts start {@code pending} with a null password and are
 * activated via a link or OTP (see {@code activation_tokens}).
 */
@Entity
@Table(name = "merchant_users", schema = "merchant")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MerchantUser {

    public static final String ROLE_OWNER = "owner";
    public static final String ROLE_CASHIER = "cashier";

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_REVOKED = "revoked";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The merchant (business) this login belongs to — {@code users.id}. */
    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    /** Cashier lock to one terminal ({@code terminals.id}); null = all merchant terminals. */
    @Column(name = "terminal_id")
    private Long terminalId;

    @Builder.Default
    private String role = ROLE_OWNER;

    private String name;

    @Column(unique = true)
    private String email;

    @Column(name = "phone_number")
    private String phoneNumber;

    @JsonIgnore
    private String password;

    @Builder.Default
    private String status = STATUS_PENDING;

    @Column(name = "email_verified_at")
    @JsonIgnore
    private LocalDateTime emailVerifiedAt;

    /** merchant_users.id of the owner who invited this cashier. */
    @Column(name = "invited_by")
    private Long invitedBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public boolean isOwner() {
        return ROLE_OWNER.equalsIgnoreCase(role);
    }

    public boolean isCashier() {
        return ROLE_CASHIER.equalsIgnoreCase(role);
    }

    public boolean isActive() {
        return STATUS_ACTIVE.equalsIgnoreCase(status);
    }

    public boolean isRevoked() {
        return STATUS_REVOKED.equalsIgnoreCase(status);
    }
}

package com.tms.report.modules.merchantuser.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.tms.report.modules.role.model.Role;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * A login for the merchant dashboard. Authentication is delegated to tms-user:
 * <ul>
 * <li><b>owner</b> — the merchant business owner. Credentials stored in
 * tms-user's {@code users} table. Identified by {@code merchantId} (the
 * tms-user users.id).</li>
 * <li><b>staff</b> (cashier, manager, etc.) — invited by the owner. Credentials
 * stored in tms-user's {@code operators} table. Identified by
 * {@code operatorId}.</li>
 * </ul>
 *
 * <p>
 * Roles and privileges are managed locally in the {@code merchant.roles} and
 * {@code merchant.role_privileges} tables. The {@code password} field is kept
 * for backward compatibility but new users authenticate via tms-user.
 */
@Entity
@Table(name = "merchant_users", schema = "merchant", indexes = {
        @Index(name = "idx_merchant_users_operator_id", columnList = "operator_id")})
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

    /**
     * For staff users: the operator id in tms-user's operators table. Null for
     * owner users who authenticate directly via tms-user's users table.
     */
    @Column(name = "operator_id", unique = true)
    private Long operatorId;

    /**
     * Cashier lock to one terminal ({@code terminals.id}); null = all merchant
     * terminals.
     */
    @Column(name = "terminal_id")
    private Long terminalId;

    @Builder.Default
    private String role = ROLE_OWNER;

    /** The database-driven role assigned to this user. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "role_id")
    private Role roleEntity;

    private String name;

    @Column(unique = true)
    private String email;

    @Column(name = "phone_number")
    private String phoneNumber;

    /**
     * Legacy password field. New users authenticate via tms-user (operators table
     * for staff, users table for owners). Kept for backward compatibility with
     * existing accounts.
     */
    @JsonIgnore
    private String password;

    @Builder.Default
    private String status = STATUS_PENDING;

    @Column(name = "email_verified_at")
    @JsonIgnore
    private LocalDateTime emailVerifiedAt;

    /** merchant_users.id of the owner who invited this staff member. */
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

    /**
     * Whether this is a staff user (authenticates via tms-user operators table).
     */
    public boolean isStaff() {
        return operatorId != null;
    }

    public boolean isActive() {
        return STATUS_ACTIVE.equalsIgnoreCase(status);
    }

    public boolean isRevoked() {
        return STATUS_REVOKED.equalsIgnoreCase(status);
    }
}

package com.tms.report.modules.admin.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tms.report.modules.role.model.Role;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "admins")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@lombok.EqualsAndHashCode(exclude = {"roles"})
@lombok.ToString(exclude = {"roles"})
public class Admin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @Column(unique = true)
    private String email;

    @Column(name = "phone_number")
    private String phoneNumber;

    /**
     * Tenant key — the bank this portal user belongs to. {@code null} together with
     * the {@code super_admin} role means a global (IRPay) user who sees all banks.
     * A non-super user is scoped to this bank's direct merchants.
     */
    @Column(name = "bank_code")
    private String bankCode;

    @JsonIgnore
    private String password;

    @Column(name = "blocked_at")
    @JsonIgnore
    private LocalDateTime blockedAt;

    @Column(name = "blocked_reason")
    @JsonIgnore
    private String blockedReason;

    @Column(name = "email_verified_at")
    @JsonIgnore
    private LocalDateTime emailVerifiedAt;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "admin_role", joinColumns = @JoinColumn(name = "admin_id"), inverseJoinColumns = @JoinColumn(name = "role_id"))
    @Builder.Default
    @JsonIgnoreProperties({"privileges"})
    private Set<Role> roles = new HashSet<>();

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public boolean isBlocked() {
        return blockedAt != null;
    }

    public String getStatus() {
        return isBlocked() ? "blocked" : "active";
    }

    public boolean isSuperAdmin() {
        return roles.stream().anyMatch(r -> "super_admin".equals(r.getCode()));
    }

    public boolean hasPrivilege(String privilegeCode) {
        if (isSuperAdmin())
            return true;
        return roles.stream().flatMap(r -> r.getPrivileges().stream()).anyMatch(p -> privilegeCode.equals(p.getCode()));
    }
}

package com.tms.report.modules.role.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * A named collection of privileges scoped to a merchant. Each merchant can
 * define custom roles (e.g. "Manager", "Support Agent") on top of the system
 * defaults ("owner", "cashier"). The {@code systemRole} flag protects built-in
 * roles from deletion.
 */
@Entity
@Table(name = "roles", schema = "merchant", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"merchant_id", "slug"})})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
@ToString(exclude = {"privileges", "users"})
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The merchant this role belongs to. Null means it's a global template. */
    @Column(name = "merchant_id")
    private Long merchantId;

    /** Human-readable name (e.g. "Owner", "Cashier", "Branch Manager"). */
    @Column(nullable = false, length = 100)
    private String name;

    /** URL-safe identifier; unique per merchant. */
    @Column(nullable = false, length = 100)
    private String slug;

    @Column(length = 500)
    private String description;

    /** If true, this role cannot be deleted or renamed by the merchant. */
    @Builder.Default
    @Column(name = "system_role", nullable = false)
    private boolean systemRole = false;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "role_privileges", schema = "merchant", joinColumns = @JoinColumn(name = "role_id"), inverseJoinColumns = @JoinColumn(name = "privilege_id"))
    private Set<Privilege> privileges = new HashSet<>();

    @OneToMany(mappedBy = "role", fetch = FetchType.LAZY)
    @JsonIgnore
    @Builder.Default
    private Set<com.tms.report.modules.merchantuser.model.MerchantUser> users = new HashSet<>();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}

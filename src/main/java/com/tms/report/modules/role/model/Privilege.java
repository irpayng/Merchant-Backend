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
 * A single permission (e.g. "view_dashboard", "manage_terminal"). Privileges
 * are assigned to roles, and roles are assigned to merchant users. The
 * {@code code} field is what Spring Security sees as a GrantedAuthority.
 */
@Entity
@Table(name = "privileges", schema = "merchant")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
@ToString(exclude = "roles")
public class Privilege {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Machine-readable code used in @PreAuthorize checks. */
    @Column(nullable = false, unique = true, length = 100)
    private String code;

    /** Human-readable label for UI display. */
    @Column(nullable = false, length = 150)
    private String name;

    /** Optional grouping for UI (e.g. "Dashboard", "Terminals", "Transactions"). */
    @Column(name = "module", length = 80)
    private String module;

    @Column(length = 500)
    private String description;

    @ManyToMany(mappedBy = "privileges", fetch = FetchType.LAZY)
    @JsonIgnore
    @Builder.Default
    private Set<Role> roles = new HashSet<>();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}

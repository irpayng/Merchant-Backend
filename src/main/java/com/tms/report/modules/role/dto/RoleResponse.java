package com.tms.report.modules.role.dto;

import com.tms.report.modules.merchantuser.model.MerchantUser;
import com.tms.report.modules.role.model.Privilege;
import com.tms.report.modules.role.model.Role;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import lombok.Builder;
import lombok.Data;

/**
 * Response DTO for roles that includes the list of assigned users (admins).
 */
@Data
@Builder
public class RoleResponse {

    private Long id;
    private Long merchantId;
    private String name;
    private String slug;
    private String description;
    private boolean systemRole;
    private Set<Privilege> privileges;
    private List<AdminSummary> admins;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * Minimal user info for the assigned users list.
     */
    @Data
    @Builder
    public static class AdminSummary {
        private Long id;
        private String name;
        private String email;
        private String status;
    }

    public static RoleResponse from(Role role, List<MerchantUser> users) {
        List<AdminSummary> admins = users.stream().map(u -> AdminSummary.builder().id(u.getId()).name(u.getName())
                .email(u.getEmail()).status(u.getStatus()).build()).toList();

        return RoleResponse.builder().id(role.getId()).merchantId(role.getMerchantId()).name(role.getName())
                .slug(role.getSlug()).description(role.getDescription()).systemRole(role.isSystemRole())
                .privileges(role.getPrivileges()).admins(admins).createdAt(role.getCreatedAt())
                .updatedAt(role.getUpdatedAt()).build();
    }
}

package com.tms.report.modules.role.controller;

import com.tms.report.core.dto.ApiResponse;
import com.tms.report.modules.role.dto.AssignRoleRequest;
import com.tms.report.modules.role.dto.CreateRoleRequest;
import com.tms.report.modules.role.dto.UpdateRoleRequest;
import com.tms.report.modules.role.model.Role;
import com.tms.report.modules.role.service.RoleService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    @GetMapping
    @PreAuthorize("hasAuthority('manage_role')")
    public ApiResponse<List<Role>> list() {
        return ApiResponse.success(roleService.listRoles());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('manage_role')")
    public ApiResponse<Role> get(@PathVariable Long id) {
        return ApiResponse.success(roleService.getRole(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('manage_role')")
    public ApiResponse<Role> create(@Valid @RequestBody CreateRoleRequest request) {
        Role role = roleService.createRole(
                request.getName(), request.getSlug(), request.getDescription(), request.getPrivilegeIds());
        return ApiResponse.success(role);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('manage_role')")
    public ApiResponse<Role> update(@PathVariable Long id, @Valid @RequestBody UpdateRoleRequest request) {
        Role role = roleService.updateRole(id, request.getName(), request.getDescription(), request.getPrivilegeIds());
        return ApiResponse.success(role);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('manage_role')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        roleService.deleteRole(id);
        return ApiResponse.success(null);
    }

    @PostMapping("/assign/{userId}")
    @PreAuthorize("hasAuthority('manage_role')")
    public ApiResponse<?> assignToUser(@PathVariable Long userId, @Valid @RequestBody AssignRoleRequest request) {
        return ApiResponse.success(roleService.assignRoleToUser(userId, request.getRoleId()));
    }
}

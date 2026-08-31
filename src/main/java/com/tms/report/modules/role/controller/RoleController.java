package com.tms.report.modules.role.controller;

import com.tms.report.core.dto.ApiResponse;
import com.tms.report.modules.merchantuser.service.MerchantUserService;
import com.tms.report.modules.role.dto.AssignRoleRequest;
import com.tms.report.modules.role.dto.CreateRoleRequest;
import com.tms.report.modules.role.dto.RoleResponse;
import com.tms.report.modules.role.dto.UpdateRoleRequest;
import com.tms.report.modules.role.service.RoleService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;
    private final MerchantUserService merchantUserService;

    @GetMapping
    @PreAuthorize("hasAuthority('manage_role')")
    public ApiResponse<List<RoleResponse>> list() {
        return ApiResponse.success(roleService.listRoles());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('manage_role')")
    public ApiResponse<RoleResponse> get(@PathVariable Long id) {
        return ApiResponse.success(roleService.getRoleWithUsers(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('manage_role')")
    public ApiResponse<RoleResponse> create(@Valid @RequestBody CreateRoleRequest request) {
        RoleResponse role = roleService.createRole(request.getName(), request.getSlug(), request.getDescription(),
                request.getPrivilegeIds());
        return ApiResponse.success(role);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('manage_role')")
    public ApiResponse<RoleResponse> update(@PathVariable Long id, @Valid @RequestBody UpdateRoleRequest request) {
        RoleResponse role = roleService.updateRole(id, request.getName(), request.getDescription(),
                request.getPrivilegeIds());
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

    @SuppressWarnings("unchecked")
    @PostMapping("/assign")
    @PreAuthorize("hasAuthority('manage_user')")
    public ApiResponse<?> assignRoles(@RequestBody Map<String, Object> body) {
        Long adminId = Long.parseLong(body.get("admin_id").toString());
        List<String> roles = (List<String>) body.get("roles");
        merchantUserService.assignRoles(adminId, roles);
        return ApiResponse.success(null);
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/unassign")
    @PreAuthorize("hasAuthority('manage_user')")
    public ApiResponse<?> unassignRoles(@RequestBody Map<String, Object> body) {
        Long adminId = Long.parseLong(body.get("admin_id").toString());
        List<String> roles = (List<String>) body.get("roles");
        merchantUserService.unassignRoles(adminId, roles);
        return ApiResponse.success(null);
    }
}

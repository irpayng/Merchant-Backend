package com.tms.report.modules.role.controller;

import com.tms.report.core.dto.ApiResponse;
import com.tms.report.modules.activity.annotation.LogActivity;
import com.tms.report.modules.admin.model.Admin;
import com.tms.report.modules.admin.repository.AdminRepository;
import com.tms.report.modules.role.model.Role;
import com.tms.report.modules.role.repository.RoleRepository;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleRepository roleRepository;
    private final AdminRepository adminRepository;

    @GetMapping("/all")
    @Transactional(readOnly = true)
    public ApiResponse<List<Role>> index() {
        List<Role> roles = roleRepository.findAll();
        // Force-initialize lazy admins collection within the transaction
        roles.forEach(r -> r.getAdmins().size());
        return ApiResponse.success(roles);
    }

    @LogActivity(action = "assign", description = "{admin} assigned a role")
    @PostMapping("/assign")
    @PreAuthorize("hasAuthority('manage_privilege')")
    public ApiResponse<Void> assign(@RequestBody Map<String, Object> body) {
        Long adminId = Long.parseLong(body.get("admin_id").toString());
        List<?> roleIds = (List<?>) body.get("roles");

        Admin admin = adminRepository.findById(adminId)
                .orElseThrow(() -> new EntityNotFoundException("Admin not found"));

        for (Object roleId : roleIds) {
            roleRepository.findById(Long.parseLong(roleId.toString())).ifPresent(admin.getRoles()::add);
        }
        adminRepository.save(admin);
        return ApiResponse.success(null);
    }

    @LogActivity(action = "unassign", description = "{admin} unassigned a role")
    @PostMapping("/unassign")
    @PreAuthorize("hasAuthority('manage_privilege')")
    public ApiResponse<Void> unassign(@RequestBody Map<String, Object> body) {
        Long adminId = Long.parseLong(body.get("admin_id").toString());
        List<?> roleIds = (List<?>) body.get("roles");

        Admin admin = adminRepository.findById(adminId)
                .orElseThrow(() -> new EntityNotFoundException("Admin not found"));

        for (Object roleId : roleIds) {
            long rid = Long.parseLong(roleId.toString());
            admin.getRoles().removeIf(r -> r.getId().equals(rid));
        }
        adminRepository.save(admin);
        return ApiResponse.success(null);
    }
}

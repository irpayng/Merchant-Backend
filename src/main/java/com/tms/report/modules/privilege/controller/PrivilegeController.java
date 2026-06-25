package com.tms.report.modules.privilege.controller;

import com.tms.report.core.dto.ApiResponse;
import com.tms.report.modules.activity.annotation.LogActivity;
import com.tms.report.modules.privilege.model.Privilege;
import com.tms.report.modules.privilege.repository.PrivilegeRepository;
import com.tms.report.modules.role.model.Role;
import com.tms.report.modules.role.repository.RoleRepository;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/roles/privileges")
@RequiredArgsConstructor
public class PrivilegeController {

    private final PrivilegeRepository privilegeRepository;
    private final RoleRepository roleRepository;

    @GetMapping("/all")
    public ApiResponse<List<Privilege>> all() {
        return ApiResponse.success(privilegeRepository.findAll());
    }

    @LogActivity(action = "assign", description = "{admin} assigned a privilege")
    @PostMapping("/assign")
    @PreAuthorize("hasAuthority('manage_privilege')")
    public ApiResponse<Void> assign(@RequestBody Map<String, Object> body) {
        String roleCode = body.get("role").toString();
        List<?> privilegeCodes = (List<?>) body.get("privileges");

        Role role = roleRepository.findByCode(roleCode)
                .orElseThrow(() -> new EntityNotFoundException("Role not found"));

        for (Object code : privilegeCodes) {
            Optional<Privilege> priv = privilegeRepository.findByCode(code.toString());
            priv.ifPresent(role.getPrivileges()::add);
        }
        roleRepository.save(role);
        return ApiResponse.success(null);
    }

    @LogActivity(action = "unassign", description = "{admin} unassigned a privilege")
    @PostMapping("/unassign")
    @PreAuthorize("hasAuthority('manage_privilege')")
    public ApiResponse<Void> unassign(@RequestBody Map<String, Object> body) {
        String roleCode = body.get("role").toString();
        List<?> privilegeCodes = (List<?>) body.get("privileges");

        Role role = roleRepository.findByCode(roleCode)
                .orElseThrow(() -> new EntityNotFoundException("Role not found"));

        for (Object code : privilegeCodes) {
            role.getPrivileges().removeIf(p -> p.getCode().equals(code.toString()));
        }
        roleRepository.save(role);
        return ApiResponse.success(null);
    }
}

package com.tms.report.modules.admin.controller;

import com.tms.report.core.dto.ApiResponse;
import com.tms.report.core.dto.PagedResponse;
import com.tms.report.core.export.XlsxExporter;
import com.tms.report.core.filter.QueryFilterHelper;
import com.tms.report.modules.activity.annotation.LogActivity;
import com.tms.report.modules.admin.dto.AdminDto;
import com.tms.report.modules.admin.dto.CreateAdminRequest;
import com.tms.report.modules.admin.dto.RoleDto;
import com.tms.report.modules.admin.dto.UpdateAdminRequest;
import com.tms.report.modules.admin.service.AdminService;
import com.tms.report.modules.role.repository.RoleRepository;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admins")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;
    private final RoleRepository roleRepository;

    @GetMapping
    public Map<String, Object> index(@RequestParam Map<String, String> params) {
        Map<String, Object> extra = new LinkedHashMap<>();
        try {
            Map<String, Object> filters = new LinkedHashMap<>();
            filters.put("roles", roleRepository.findAll().stream()
                    .map(r -> Map.of("id", (Object) r.getId(), "name", (Object) r.getName())).toList());
            extra.put("filters", filters);
        } catch (Exception e) {
            extra.put("filters", Map.of());
        }

        return PagedResponse.from(adminService.index(params), "/admins", extra);
    }

    @GetMapping("/download")
    public void download(@RequestParam Map<String, String> params, HttpServletResponse response) throws Exception {
        XlsxExporter.streamPaged(response, "admins",
                new String[]{"ID", "Name", "Email", "Roles", "Status", "Created At"}, 1000,
                (page, size) -> adminService.index(QueryFilterHelper.pageParams(params, page, size)).getContent(),
                row -> new String[]{String.valueOf(row.getId()), row.getName(), row.getEmail(), row.getRoles() != null
                        ? row.getRoles().stream().map(RoleDto::getName).reduce((a, b) -> a + "; " + b).orElse("")
                        : "", row.getStatus(), row.getCreatedAt() != null ? row.getCreatedAt().toString() : ""});
    }

    @GetMapping("/{id}")
    public ApiResponse<AdminDto> show(@PathVariable Long id) {
        return ApiResponse.success(adminService.show(id));
    }

    @LogActivity(action = "create", description = "{admin} created a new admin account for {body.name}")
    @PostMapping
    @PreAuthorize("hasAnyAuthority('manage_privilege', 'manage_bank_users')")
    public ApiResponse<AdminDto> store(@Valid @RequestBody CreateAdminRequest request) {
        return ApiResponse.success(adminService.store(request));
    }

    @LogActivity(action = "update", description = "{admin} updated the admin account of {user}", userFrom = "admin:id")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('manage_privilege')")
    public ApiResponse<AdminDto> update(@PathVariable Long id, @Valid @RequestBody UpdateAdminRequest request) {
        return ApiResponse.success(adminService.update(id, request));
    }

    @LogActivity(action = "delete", description = "{admin} deleted the admin account of {user}", userFrom = "admin:id")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('manage_privilege')")
    public ApiResponse<Void> destroy(@PathVariable Long id) {
        adminService.destroy(id);
        return ApiResponse.success(null);
    }

    @LogActivity(action = "block", description = "{admin} blocked the admin account of {user}", userFrom = "admin:id")
    @PostMapping("/{id}/block")
    @PreAuthorize("hasAuthority('manage_privilege')")
    public ApiResponse<AdminDto> block(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return ApiResponse.success(adminService.block(id, body.get("reason")));
    }

    @LogActivity(action = "unblock", description = "{admin} unblocked the admin account of {user}", userFrom = "admin:id")
    @PostMapping("/{id}/unblock")
    @PreAuthorize("hasAuthority('manage_privilege')")
    public ApiResponse<AdminDto> unblock(@PathVariable Long id) {
        return ApiResponse.success(adminService.unblock(id));
    }
}

package com.tms.report.modules.role.controller;

import com.tms.report.core.dto.ApiResponse;
import com.tms.report.modules.role.model.Privilege;
import com.tms.report.modules.role.service.PrivilegeService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Read-only endpoint for listing available privileges. Merchants cannot
 * create or delete privileges — they are system-defined.
 */
@RestController
@RequestMapping("/privileges")
@RequiredArgsConstructor
public class PrivilegeController {

    private final PrivilegeService privilegeService;

    @GetMapping
    @PreAuthorize("hasAuthority('manage_role')")
    public ApiResponse<List<Privilege>> list(@RequestParam(required = false) String module) {
        List<Privilege> result = module != null ? privilegeService.listByModule(module) : privilegeService.listAll();
        return ApiResponse.success(result);
    }
}

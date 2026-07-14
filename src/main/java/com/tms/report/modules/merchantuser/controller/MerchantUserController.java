package com.tms.report.modules.merchantuser.controller;

import com.tms.report.core.dto.ApiResponse;
import com.tms.report.core.dto.PagedResponse;
import com.tms.report.modules.merchantuser.service.MerchantUserService;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admins")
@RequiredArgsConstructor
public class MerchantUserController {

    private final MerchantUserService merchantUserService;

    @GetMapping
    @PreAuthorize("hasAuthority('manage_user')")
    public Map<String, Object> list(@RequestParam Map<String, String> params) {
        Page<Map<String, Object>> page = merchantUserService.list(params);

        Map<String, Object> extra = new LinkedHashMap<>();
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("roles", merchantUserService.listRolesForFilter());
        extra.put("filters", filters);
        extra.put("roles", merchantUserService.listRolesForFilter());

        return PagedResponse.from(page, "/admins", extra);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('manage_user')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(@RequestBody Map<String, Object> data) {
        Map<String, Object> user = merchantUserService.create(data);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.<Map<String, Object>>builder().code(201)
                .message("User created successfully").data(user).build());
    }
}

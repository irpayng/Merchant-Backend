package com.tms.report.modules.audit.controller;

import com.tms.report.core.dto.ApiResponse;
import com.tms.report.core.dto.PagedResponse;
import com.tms.report.core.security.MerchantScope;
import com.tms.report.modules.audit.model.AuditLog;
import com.tms.report.modules.audit.repository.AuditLogRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Controller for viewing audit logs. Restricted to merchant owners (merchants
 * with manage_audit privilege).
 */
@RestController
@RequestMapping("/activities")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogRepository auditLogRepository;
    private final MerchantScope merchantScope;

    /**
     * GET /activities — list audit logs for the current merchant. Supports
     * pagination and search.
     */
    @GetMapping
    @PreAuthorize("hasAuthority('manage_audit')")
    public Map<String, Object> index(@RequestParam Map<String, String> params) {
        int page = Integer.parseInt(params.getOrDefault("page", "1")) - 1;
        int limit = Integer.parseInt(params.getOrDefault("limit", "25"));
        String search = params.get("search");
        String action = params.get("action");
        String module = params.get("module");

        Long merchantId = merchantScope.merchantId();
        if (merchantId == null) {
            return PagedResponse.from(Page.empty(), "/activities");
        }

        var pageable = PageRequest.of(page, limit);
        Page<AuditLog> result;

        if (search != null && !search.isBlank()) {
            result = auditLogRepository.searchByMerchant(merchantId, search.trim(), pageable);
        } else if (action != null && !action.isBlank()) {
            result = auditLogRepository.findByMerchantIdAndActionContainingIgnoreCaseOrderByCreatedAtDesc(merchantId,
                    action.trim(), pageable);
        } else if (module != null && !module.isBlank()) {
            result = auditLogRepository.findByMerchantIdAndModuleOrderByCreatedAtDesc(merchantId, module.trim(),
                    pageable);
        } else {
            result = auditLogRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId, pageable);
        }

        // Build filters for the frontend dropdown
        Map<String, Object> extra = new LinkedHashMap<>();
        try {
            List<String> actions = auditLogRepository.findDistinctActionsByMerchantId(merchantId);
            List<String> modules = auditLogRepository.findDistinctModulesByMerchantId(merchantId);

            extra.put("filters", Map.of("actions", actions.stream().map(a -> Map.of("id", a, "name", a)).toList(),
                    "modules", modules.stream().map(m -> Map.of("id", m, "name", m)).toList()));
        } catch (Exception e) {
            extra.put("filters", Map.of("actions", List.of(), "modules", List.of()));
        }

        return PagedResponse.from(result, "/activities", extra);
    }

    /**
     * GET /activities/{id} — get a single audit log entry by ID.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('manage_audit')")
    public ApiResponse<AuditLog> show(@PathVariable Long id) {
        Long merchantId = merchantScope.merchantId();
        if (merchantId == null) {
            return ApiResponse.error(404, "Audit log not found");
        }

        return auditLogRepository.findByIdAndMerchantId(id, merchantId).map(ApiResponse::success)
                .orElse(ApiResponse.error(404, "Audit log not found"));
    }
}

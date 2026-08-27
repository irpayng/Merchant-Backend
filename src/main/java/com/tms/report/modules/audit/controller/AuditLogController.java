package com.tms.report.modules.audit.controller;

import com.tms.report.core.dto.PagedResponse;
import com.tms.report.core.security.MerchantScope;
import com.tms.report.modules.audit.dto.AuditLogResponse;
import com.tms.report.modules.audit.model.AuditLog;
import com.tms.report.modules.audit.repository.AuditLogRepository;
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

        Long merchantId = merchantScope.merchantId();
        if (merchantId == null) {
            return PagedResponse.from(Page.empty(), "/activities");
        }

        var pageable = PageRequest.of(page, limit);
        Page<AuditLog> result;

        if (search != null && !search.isBlank()) {
            result = auditLogRepository.searchByMerchant(merchantId, search.trim(), pageable);
        } else {
            result = auditLogRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId, pageable);
        }

        // Map to response DTOs for frontend compatibility
        Page<AuditLogResponse> mapped = result.map(AuditLogResponse::from);
        return PagedResponse.from(mapped, "/activities");
    }
}

package com.tms.report.modules.onboarding.controller;

import com.tms.report.core.dto.ApiResponse;
import com.tms.report.core.dto.PagedResponse;
import com.tms.report.core.export.XlsxExporter;
import com.tms.report.core.filter.QueryFilterHelper;
import com.tms.report.modules.onboarding.service.OnboardingService;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Read-only admin view of agent/merchant onboardings replicated from the
 * onboarding-service. Lets ops monitor sign-ups in progress and inspect a
 * single onboarding's captured details (BVN lookup result, selfie, address).
 */
@RestController
@RequestMapping("/onboardings")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('manage_kyc')")
public class OnboardingController {

    private final OnboardingService service;

    @GetMapping
    public Map<String, Object> index(@RequestParam Map<String, String> params) {
        Map<String, Object> extra = new LinkedHashMap<>();

        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("statuses", List.of(Map.of("name", "Completed", "code", "completed"),
                Map.of("name", "In Progress", "code", "in_progress"), Map.of("name", "Started", "code", "started")));
        extra.put("filters", filters);
        extra.put("stats", service.getSummary(params));

        return PagedResponse.from(service.index(params), "/onboardings", extra);
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> show(@PathVariable Long id) {
        return ApiResponse.success(service.showDetail(id));
    }

    @GetMapping("/download")
    public void download(@RequestParam Map<String, String> params, HttpServletResponse response) throws Exception {
        XlsxExporter.streamPaged(response, "onboardings",
                new String[]{"ID", "Reference", "Name", "Email", "Phone Number", "BVN Phone", "BVN Validated",
                        "Phone Validated", "Email Validated", "Liveness Validated", "Status", "Created At"},
                1000, (page, size) -> service.index(QueryFilterHelper.pageParams(params, page, size)).getContent(),
                row -> new String[]{String.valueOf(row.getId()), nullSafe(row.getReference()), nullSafe(row.getName()),
                        nullSafe(row.getEmail()), nullSafe(row.getPhoneNumber()), nullSafe(row.getBvnPhoneNumber()),
                        yesNo(row.getBvnIsValidated()), yesNo(row.getPhoneNumberIsValidated()),
                        yesNo(row.getEmailIsValidated()), yesNo(row.getLivelinessIsValidated()),
                        nullSafe(row.getStatus()), row.getCreatedAt() != null ? row.getCreatedAt().toString() : ""});
    }

    private static String nullSafe(String s) {
        return s != null ? s : "";
    }

    private static String yesNo(Boolean b) {
        return Boolean.TRUE.equals(b) ? "yes" : "no";
    }
}

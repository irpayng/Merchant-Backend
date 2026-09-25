package com.tms.report.modules.deviceactivity.controller;

import com.tms.report.core.dto.ApiResponse;
import com.tms.report.core.dto.PagedResponse;
import com.tms.report.core.export.XlsxExporter;
import com.tms.report.core.filter.QueryFilterHelper;
import com.tms.report.modules.deviceactivity.service.DeviceActivityService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * REST API for device activities — operator login/logout events on POS
 * terminals. Scoped to the authenticated merchant's terminals only.
 */
@RestController
@RequestMapping("/device-activities")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('manage_terminal')")
public class DeviceActivityController {

    private final DeviceActivityService deviceActivityService;

    /**
     * GET /device-activities — paginated list of device activities with optional
     * filters.
     * <p>
     * Query params:
     * <ul>
     * <li>{@code page} — 1-indexed page number (default 1)</li>
     * <li>{@code limit} — items per page (default 15)</li>
     * <li>{@code search} — free-text search across serial, operator name,
     * action</li>
     * <li>{@code action} — filter by action type (login/logout)</li>
     * <li>{@code device_serial} — filter by specific terminal serial</li>
     * <li>{@code operator_id} — filter by specific operator</li>
     * <li>{@code dates[]} — date range filter (start, end)</li>
     * </ul>
     */
    @GetMapping
    public Map<String, Object> index(@RequestParam Map<String, String> params, HttpServletRequest request) {
        extractDates(request, params);
        return PagedResponse.from(deviceActivityService.index(params), "/device-activities",
                Map.of("filters", deviceActivityService.getFilters(), "stats", deviceActivityService.getStats()));
    }

    /**
     * GET /device-activities/{id} — single device activity detail.
     */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> show(@PathVariable Long id) {
        return ApiResponse.success(deviceActivityService.show(id));
    }

    /**
     * GET /device-activities/download — XLSX export of filtered device activities.
     */
    @GetMapping("/download")
    public void download(@RequestParam Map<String, String> params, HttpServletRequest request,
            HttpServletResponse response) throws Exception {
        extractDates(request, params);
        XlsxExporter.streamPaged(response, "device-activities",
                new String[]{"ID", "Device Serial", "Operator", "Action", "Platform", "App Version", "Time"}, 1000,
                (page, size) -> deviceActivityService.index(QueryFilterHelper.pageParams(params, page, size))
                        .getContent(),
                row -> new String[]{String.valueOf(row.get("id")), str(row.get("device_serial")),
                        str(row.get("operator_name")), str(row.get("action_label")), str(row.get("platform")),
                        str(row.get("app_version")), str(row.get("created_at"))});
    }

    private void extractDates(HttpServletRequest request, Map<String, String> params) {
        String[] dates = request.getParameterValues("dates[]");
        if (dates != null && dates.length >= 2) {
            params.put("dates[0]", dates[0]);
            params.put("dates[1]", dates[1]);
        }
    }

    private String str(Object o) {
        return o != null ? o.toString() : "";
    }
}

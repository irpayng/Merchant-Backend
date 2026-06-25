package com.tms.report.modules.activity.controller;

import com.tms.report.core.dto.ApiResponse;
import com.tms.report.core.dto.PagedResponse;
import com.tms.report.core.export.XlsxExporter;
import com.tms.report.core.filter.QueryFilterHelper;
import com.tms.report.modules.activity.service.ActivityService;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/activities")
@RequiredArgsConstructor
public class ActivityController {

    private final ActivityService activityService;

    @GetMapping
    public Map<String, Object> index(@RequestParam Map<String, String> params) {
        return PagedResponse.from(activityService.index(params), "/activities");
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> show(@PathVariable Long id) {
        return ApiResponse.success(activityService.show(id));
    }

    @GetMapping("/download")
    public void download(@RequestParam Map<String, String> params, HttpServletResponse response) throws Exception {
        XlsxExporter.streamPaged(response, "activities",
                new String[]{"ID", "Action", "Description", "Admin", "Actionable Type", "Actionable ID", "Created At"},
                1000,
                (page, size) -> activityService.index(QueryFilterHelper.pageParams(params, page, size)).getContent(),
                row -> new String[]{String.valueOf(row.getId()), row.getAction(), row.getDescription(),
                        row.getAdminName(), row.getActionableType(),
                        row.getActionableId() != null ? row.getActionableId().toString() : "",
                        row.getCreatedAt() != null ? row.getCreatedAt().toString() : ""});
    }
}

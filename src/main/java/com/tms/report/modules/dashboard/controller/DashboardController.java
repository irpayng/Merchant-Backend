package com.tms.report.modules.dashboard.controller;

import com.tms.report.modules.dashboard.service.DashboardService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/dashboard")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('view_dashboard')")
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping
    public Map<String, Object> index(@RequestParam Map<String, String> params) {
        return dashboardService.getDashboardData(params);
    }
}

package com.kll.deliverydispatch.dashboard.controller;

import com.kll.deliverydispatch.dashboard.dto.DashboardSummary;
import com.kll.deliverydispatch.dashboard.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {
    private final DashboardService dashboardService;

    @GetMapping("/summary")
    public DashboardSummary summary() {
        return dashboardService.summary();
    }
}

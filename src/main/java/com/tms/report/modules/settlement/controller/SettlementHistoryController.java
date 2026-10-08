package com.tms.report.modules.settlement.controller;

import com.tms.report.core.dto.ApiResponse;
import com.tms.report.core.security.MerchantScope;
import com.tms.report.modules.grpc.service.GrpcClient;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Settlement history endpoint for merchant dashboard. Merchants can view the
 * history of their settlements including resolution status changes.
 */
@RestController
@RequestMapping("/settlement-history")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('manage_settlement')")
public class SettlementHistoryController {

    private final MerchantScope merchantScope;
    private final GrpcClient grpcClient;

    /**
     * Get settlement history for the current merchant.
     */
    @GetMapping
    public ApiResponse<Map<String, Object>> getHistory(@RequestParam(required = false) String reference,
            @RequestParam(required = false) String startDate, @RequestParam(required = false) String endDate,
            @RequestParam(required = false, defaultValue = "100") int limit) {
        Long merchantId = merchantScope.merchantId();
        if (merchantId == null) {
            return ApiResponse.error(404, "Merchant not found");
        }

        Map<String, Object> result = grpcClient.getSettlementHistory(reference, String.valueOf(merchantId), startDate,
                endDate, limit);

        return ApiResponse.success(result);
    }

    /**
     * Get settlement history for a specific settlement reference.
     */
    @GetMapping("/{reference}")
    public ApiResponse<Map<String, Object>> getHistoryByReference(@PathVariable String reference) {
        Long merchantId = merchantScope.merchantId();
        if (merchantId == null) {
            return ApiResponse.error(404, "Merchant not found");
        }

        Map<String, Object> result = grpcClient.getSettlementHistory(reference, String.valueOf(merchantId), null, null,
                100);

        return ApiResponse.success(result);
    }

    /**
     * Get settlements by resolution status for the current merchant.
     */
    @GetMapping("/by-status/{status}")
    public ApiResponse<Map<String, Object>> getSettlementsByStatus(@PathVariable String status,
            @RequestParam(required = false) String startDate, @RequestParam(required = false) String endDate,
            @RequestParam(required = false, defaultValue = "100") int limit) {
        Long merchantId = merchantScope.merchantId();
        if (merchantId == null) {
            return ApiResponse.error(404, "Merchant not found");
        }

        Map<String, Object> result = grpcClient.getSettlementsByResolutionStatus(String.valueOf(merchantId),
                status.toUpperCase(), startDate, endDate, limit);

        return ApiResponse.success(result);
    }

    /**
     * Get summary of settlements by resolution status.
     */
    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> getResolutionSummary(@RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        Long merchantId = merchantScope.merchantId();
        if (merchantId == null) {
            return ApiResponse.error(404, "Merchant not found");
        }

        String merchantIdStr = String.valueOf(merchantId);

        // Get counts for each status
        Map<String, Object> pending = grpcClient.getSettlementsByResolutionStatus(merchantIdStr, "PENDING", startDate,
                endDate, 1000);
        Map<String, Object> scheduled = grpcClient.getSettlementsByResolutionStatus(merchantIdStr, "SCHEDULED",
                startDate, endDate, 1000);
        Map<String, Object> resolved = grpcClient.getSettlementsByResolutionStatus(merchantIdStr, "RESOLVED", startDate,
                endDate, 1000);
        Map<String, Object> paid = grpcClient.getSettlementsByResolutionStatus(merchantIdStr, "PAID", startDate,
                endDate, 1000);

        return ApiResponse.success(Map.of("pending_count", getSettlementCount(pending), "scheduled_count",
                getSettlementCount(scheduled), "resolved_count", getSettlementCount(resolved), "paid_count",
                getSettlementCount(paid), "pending_amount", getSettlementAmount(pending), "scheduled_amount",
                getSettlementAmount(scheduled), "resolved_amount", getSettlementAmount(resolved), "paid_amount",
                getSettlementAmount(paid)));
    }

    @SuppressWarnings("unchecked")
    private int getSettlementCount(Map<String, Object> result) {
        Object settlements = result.get("settlements");
        if (settlements instanceof List<?> list) {
            return list.size();
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private double getSettlementAmount(Map<String, Object> result) {
        Object settlements = result.get("settlements");
        if (settlements instanceof List<?> list) {
            return list.stream().filter(s -> s instanceof Map).map(s -> (Map<String, Object>) s).mapToDouble(s -> {
                Object amount = s.get("amount");
                if (amount instanceof Number n) {
                    return n.doubleValue();
                }
                return 0.0;
            }).sum();
        }
        return 0.0;
    }
}

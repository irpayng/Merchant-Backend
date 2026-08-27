package com.tms.report.modules.statement.controller;

import com.tms.report.core.dto.PagedResponse;
import com.tms.report.core.security.MerchantScope;
import com.tms.report.modules.grpc.service.GrpcClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Wallet statements endpoint for the merchant dashboard. Returns the
 * authenticated merchant's wallet transaction history (credits and debits).
 */
@RestController
@RequestMapping("/statements")
@RequiredArgsConstructor
public class StatementController {

    private final GrpcClient grpcClient;
    private final MerchantScope merchantScope;

    @GetMapping
    @SuppressWarnings("unchecked")
    public Map<String, Object> index(@RequestParam Map<String, String> params) {
        Long merchantId = merchantScope.merchantId();
        if (merchantId == null) {
            return PagedResponse.empty("/statements");
        }

        int page = Integer.parseInt(params.getOrDefault("page", "1"));
        int limit = Integer.parseInt(params.getOrDefault("limit", "20"));
        String walletType = params.get("wallet_type");
        String startDate = params.get("start_date");
        String endDate = params.get("end_date");
        String type = params.get("type");

        // Handle dates[] array format from frontend
        String[] dates = null;
        if (params.containsKey("dates[0]") && params.containsKey("dates[1]")) {
            startDate = params.get("dates[0]");
            endDate = params.get("dates[1]");
        }

        Map<String, Object> result = grpcClient.listStatements(merchantId, walletType, page, limit, startDate, endDate,
                type);

        List<Map<String, Object>> statements = (List<Map<String, Object>>) result.get("statements");
        long total = ((Number) result.get("total")).longValue();

        // Transform statements to match frontend expected format
        List<Map<String, Object>> transformedStatements = statements.stream().map(s -> {
            Map<String, Object> stmt = new LinkedHashMap<>();
            stmt.put("id", s.get("id"));
            stmt.put("type", s.get("type"));
            stmt.put("amount", s.get("amount"));
            stmt.put("previous_balance", s.get("previous_balance"));
            stmt.put("current_balance", s.get("current_balance"));
            stmt.put("description", s.get("description"));
            stmt.put("category", s.get("category"));
            stmt.put("source_type", s.get("source_type"));
            stmt.put("source_reference", s.get("source_reference"));
            stmt.put("wallet_type", s.get("wallet_type"));
            stmt.put("created_at", s.get("created_at"));

            // Add status for badge display
            Map<String, String> status = new LinkedHashMap<>();
            String entryType = (String) s.get("type");
            status.put("code", entryType);
            status.put("description", "credit".equals(entryType) ? "Credit" : "Debit");
            stmt.put("status", status);

            return stmt;
        }).toList();

        // Build filters for frontend
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("wallet_types", List.of(Map.of("id", "default", "name", "Main Wallet"),
                Map.of("id", "commission", "name", "Commission Wallet")));
        filters.put("types", List.of(Map.of("id", "credit", "name", "Credit"), Map.of("id", "debit", "name", "Debit")));

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("filters", filters);

        return PagedResponse.from(transformedStatements, total, page, limit, "/statements", extra);
    }
}

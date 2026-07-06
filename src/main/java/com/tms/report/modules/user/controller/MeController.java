package com.tms.report.modules.user.controller;

import com.tms.report.core.dto.ApiResponse;
import com.tms.report.core.dto.PagedResponse;
import com.tms.report.core.exception.AppException;
import com.tms.report.core.export.CsvExporter;
import com.tms.report.core.export.XlsxExporter;
import com.tms.report.core.filter.QueryFilterHelper;
import com.tms.report.core.security.MerchantScope;
import com.tms.report.modules.user.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The authenticated merchant's own surfaces that aren't already served by the
 * auto-scoped collection endpoints ({@code /transactions}, {@code /terminals},
 * {@code /dashboard} are all merchant-scoped via {@link MerchantScope}). Here we
 * expose the merchant's own profile, statements, and wallets — everything keyed
 * off the login's {@code merchant_id}, never a path id, so a merchant can only
 * ever read its own data.
 */
@RestController
@RequestMapping("/me")
@RequiredArgsConstructor
public class MeController {

    private final MerchantScope merchantScope;
    private final UserService userService;

    private Long merchantId() {
        Long id = merchantScope.merchantId();
        if (id == null) {
            throw new AppException("Not authenticated", HttpStatus.UNAUTHORIZED);
        }
        return id;
    }

    /** The merchant's business profile (KYC, location, wallets, stats). */
    @GetMapping("/profile")
    public ApiResponse<Map<String, Object>> profile() {
        return ApiResponse.success(userService.showDetail(merchantId()));
    }

    @GetMapping("/statements")
    public Map<String, Object> statements(@RequestParam Map<String, String> params, HttpServletRequest request) {
        Long id = merchantId();
        extractDates(request, params);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("filters", Map.of("type", List.of("credit", "debit"), "owner_type", List.of("users", "providers")));
        extra.put("stats", userService.getUserStatementStats(id, params));
        return PagedResponse.from(userService.getUserStatements(id, params), "/me/statements", extra);
    }

    @GetMapping("/statements/download")
    public void downloadStatements(@RequestParam Map<String, String> params, HttpServletRequest request,
            HttpServletResponse response) throws Exception {
        Long id = merchantId();
        extractDates(request, params);
        XlsxExporter.streamPaged(response, "statements", new String[]{"ID", "Type", "Amount", "Description",
                "Previous Balance", "Current Balance", "Wallet Type", "Date"}, 1000,
                (page, size) -> userService.getUserStatements(id, QueryFilterHelper.pageParams(params, page, size))
                        .getContent(),
                row -> new String[]{String.valueOf(row.get("id")), String.valueOf(row.getOrDefault("type", "")),
                        CsvExporter.formatCurrency(row.get("amount")),
                        String.valueOf(row.getOrDefault("description", "")),
                        CsvExporter.formatCurrency(row.get("previous_balance")),
                        CsvExporter.formatCurrency(row.get("current_balance")),
                        String.valueOf(row.getOrDefault("wallet_type", "")),
                        row.get("created_at") != null ? row.get("created_at").toString() : ""});
    }

    @GetMapping("/wallets")
    public Map<String, Object> wallets(@RequestParam Map<String, String> params) {
        Long id = merchantId();
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("filters", Map.of("types", List.of("default", "commission")));
        extra.put("stats", userService.getUserWalletStats(id, params));
        return PagedResponse.from(userService.getUserWallets(id, params), "/me/wallets", extra);
    }

    private void extractDates(HttpServletRequest request, Map<String, String> params) {
        String[] dates = request.getParameterValues("dates[]");
        if (dates != null && dates.length >= 2) {
            params.put("dates[0]", dates[0]);
            params.put("dates[1]", dates[1]);
        }
    }
}

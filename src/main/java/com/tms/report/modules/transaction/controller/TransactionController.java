package com.tms.report.modules.transaction.controller;

import com.tms.report.core.dto.ApiResponse;
import com.tms.report.core.dto.PagedResponse;
import com.tms.report.core.export.CsvExporter;
import com.tms.report.core.export.XlsxExporter;
import com.tms.report.modules.transaction.dto.TransactionDto;
import com.tms.report.modules.transaction.service.TransactionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

/**
 * Read-only transaction monitoring for the super-merchant (bank) portal.
 *
 * <p>
 * All admin mutations present in the internal console — requery, mark
 * completed/failed, card reversal, virtual-funding replay, and the raw
 * provider-requery view — are intentionally absent. Banks observe transaction
 * activity across their terminal estate; they do not operate on transactions.
 */
@RestController
@RequestMapping("/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;

    @GetMapping
    public Map<String, Object> index(@RequestParam Map<String, String> params) {
        Page<TransactionDto> page = transactionService.index(params);

        Map<String, Object> extra = new LinkedHashMap<>();
        try {
            extra.put("filters", transactionService.filters());
            extra.put("stats", transactionService.getSummary(params));
        } catch (Exception e) {
            extra.put("filters", Map.of());
            extra.put("stats", Map.of());
        }

        return PagedResponse.from(page, "/transactions", extra);
    }

    @GetMapping("/download")
    public void download(@RequestParam Map<String, String> params, HttpServletRequest request,
            HttpServletResponse response) throws Exception {
        String[] dates = request.getParameterValues("dates[]");
        if (dates != null && dates.length >= 2) {
            params.put("dates[0]", dates[0]);
            params.put("dates[1]", dates[1]);
        }
        Set<String> categories = new LinkedHashSet<>();
        for (String code : transactionService.exportProductCodes(params)) {
            categories.add(category(code));
        }
        String layout = categories.size() == 1 ? categories.iterator().next() : "general";

        List<String[]> columns = columnsFor(layout);
        String[] headers = columns.stream().map(c -> c[0]).toArray(String[]::new);
        String[] keys = columns.stream().map(c -> c[1]).toArray(String[]::new);

        XlsxExporter.stream(response, "transactions", headers,
                sink -> transactionService.streamExportRows(params, row -> {
                    String[] vals = new String[keys.length];
                    for (int i = 0; i < keys.length; i++) {
                        if (CURRENCY_KEYS.contains(keys[i])) {
                            vals[i] = CsvExporter.formatCurrency(row.get(keys[i]));
                        } else {
                            Object v = row.get(keys[i]);
                            vals[i] = v != null ? v.toString() : "";
                        }
                    }
                    sink.row(vals);
                }));
    }

    /** Row keys rendered as formatted currency (commas, 2dp) in the export. */
    private static final Set<String> CURRENCY_KEYS = Set.of("amount", "service_fee", "agent_commission",
            "aggregator_commission", "super_aggregator_commission", "amount_to_pay");

    /**
     * Columns common to every layout. Provider and platform-revenue
     * (company_commission, provider_cost) columns are intentionally omitted for the
     * bank portal.
     */
    private static final List<String[]> BASE_COLUMNS = List.of(new String[]{"Reference", "reference"},
            new String[]{"Date", "created_at"}, new String[]{"User Name", "user_name"},
            new String[]{"User Email", "user_email"}, new String[]{"Product", "product"},
            new String[]{"Channel", "channel"}, new String[]{"Payment Method", "payment_method"},
            new String[]{"Amount", "amount"}, new String[]{"Service Fee", "service_fee"},
            new String[]{"Agent Commission", "agent_commission"},
            new String[]{"Aggregator Commission", "aggregator_commission"},
            new String[]{"Super Aggregator Commission", "super_aggregator_commission"},
            new String[]{"Amount to Pay", "amount_to_pay"}, new String[]{"Status", "status"});

    private List<String[]> columnsFor(String layout) {
        List<String[]> cols = new ArrayList<>(BASE_COLUMNS);
        switch (layout) {
            case "transfer" -> {
                cols.add(new String[]{"Bank Name", "bank_name"});
                cols.add(new String[]{"Account Number", "account_number"});
                cols.add(new String[]{"Account Name", "account_name"});
                cols.add(new String[]{"Beneficiary", "beneficiary"});
                cols.add(new String[]{"Session ID", "session_id"});
            }
            case "card" -> {
                cols.add(new String[]{"RRN", "rrn"});
                cols.add(new String[]{"STAN", "stan"});
                cols.add(new String[]{"Auth Code", "auth_code"});
                cols.add(new String[]{"Card Number", "masked_pan"});
                cols.add(new String[]{"Terminal Serial", "terminal_serial"});
            }
            case "electricity" -> {
                cols.add(new String[]{"Disco", "disco"});
                cols.add(new String[]{"Meter Number", "meter_number"});
                cols.add(new String[]{"Meter Type", "meter_type"});
                cols.add(new String[]{"Token", "token"});
                cols.add(new String[]{"Units", "units"});
                cols.add(new String[]{"Customer Name", "beneficiary"});
                cols.add(new String[]{"Phone Number", "phone_number"});
            }
            case "airtime_data" -> {
                cols.add(new String[]{"Network", "network"});
                cols.add(new String[]{"Phone Number", "phone_number"});
            }
            case "cable" -> {
                cols.add(new String[]{"Smartcard Number", "account_number"});
                cols.add(new String[]{"Customer Name", "beneficiary"});
                cols.add(new String[]{"Phone Number", "phone_number"});
            }
            default -> {
                cols.add(new String[]{"Session ID", "session_id"});
                cols.add(new String[]{"RRN", "rrn"});
                cols.add(new String[]{"STAN", "stan"});
                cols.add(new String[]{"Card Number", "masked_pan"});
                cols.add(new String[]{"Bank Name", "bank_name"});
                cols.add(new String[]{"Account Number", "account_number"});
                cols.add(new String[]{"Account Name", "account_name"});
                cols.add(new String[]{"Beneficiary", "beneficiary"});
                cols.add(new String[]{"Phone Number", "phone_number"});
                cols.add(new String[]{"Meter Number", "meter_number"});
                cols.add(new String[]{"Token", "token"});
            }
        }
        return cols;
    }

    private String category(String productCode) {
        if (productCode == null) {
            return "other";
        }
        String code = productCode.trim().replaceAll("([a-z0-9])([A-Z])", "$1-$2").replace('_', '-').toLowerCase();
        return switch (code) {
            case "bank-transfer", "local-transfer", "virtual-funding", "commission-transfer" -> "transfer";
            case "withdrawal", "purchase", "cash-advance", "cashback", "refund", "pre-auth", "completion", "complete",
                    "balance-inquiry", "reversal" ->
                "card";
            case "electricity", "power" -> "electricity";
            case "airtime", "data" -> "airtime_data";
            case "multichoice" -> "cable";
            default -> "other";
        };
    }

    @GetMapping("/{idOrRef}")
    public ApiResponse<Map<String, Object>> show(@PathVariable String idOrRef) {
        return ApiResponse.success(transactionService.show(idOrRef));
    }

    @GetMapping("/filters")
    public ApiResponse<Map<String, Object>> filters() {
        return ApiResponse.success(transactionService.filters());
    }

    @GetMapping("/get-summary")
    public ApiResponse<Map<String, Object>> getSummary(@RequestParam Map<String, String> params) {
        return ApiResponse.success(transactionService.getSummary(params));
    }

    @GetMapping("/charts/channels")
    public ApiResponse<Map<String, Object>> channelChart(@RequestParam Map<String, String> params) {
        try {
            return ApiResponse.success(transactionService.getChannelChart(params));
        } catch (Exception e) {
            return ApiResponse.success(Map.of("categories", java.util.List.of(), "series", java.util.List.of()));
        }
    }

    @GetMapping("/charts/products")
    public ApiResponse<Map<String, Object>> productChart(@RequestParam Map<String, String> params) {
        try {
            return ApiResponse.success(transactionService.getProductChart(params));
        } catch (Exception e) {
            return ApiResponse.success(Map.of("categories", java.util.List.of(), "series", java.util.List.of()));
        }
    }

    @GetMapping("/charts/payment-methods")
    public ApiResponse<Map<String, Object>> paymentMethodChart(@RequestParam Map<String, String> params) {
        try {
            return ApiResponse.success(transactionService.getPaymentMethodChart(params));
        } catch (Exception e) {
            return ApiResponse.success(Map.of("categories", java.util.List.of(), "series", java.util.List.of()));
        }
    }

    @GetMapping("/charts/time-volume")
    public ApiResponse<Map<String, Object>> timeVolumeChart(@RequestParam Map<String, String> params) {
        try {
            return ApiResponse.success(transactionService.getTimeVolumeChart(params));
        } catch (Exception e) {
            return ApiResponse.success(Map.of("categories", java.util.List.of(), "series", java.util.List.of()));
        }
    }

    @GetMapping("/charts/location-distribution")
    public ApiResponse<Map<String, Object>> locationDistributionChart(@RequestParam Map<String, String> params) {
        try {
            return ApiResponse.success(transactionService.getLocationDistribution(params));
        } catch (Exception e) {
            return ApiResponse.success(Map.of("categories", java.util.List.of(), "count_series", java.util.List.of(),
                    "amount_series", java.util.List.of()));
        }
    }
}

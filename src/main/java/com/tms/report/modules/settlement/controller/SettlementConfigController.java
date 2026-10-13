package com.tms.report.modules.settlement.controller;

import com.tms.report.core.dto.ApiResponse;
import com.tms.report.core.security.MerchantScope;
import com.tms.report.modules.grpc.service.GrpcClient;
import com.tms.report.modules.settlement.dto.ApprovalRequestResponse;
import com.tms.report.modules.settlement.dto.SettlementAccountDto;
import com.tms.report.modules.settlement.dto.SettlementAccountRequestDto;
import com.tms.report.modules.settlement.dto.SettlementConfigResponse;
import com.tms.report.modules.settlement.service.SettlementApprovalService;
import jakarta.persistence.EntityManager;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Settlement configuration endpoints for merchant dashboard. Merchants can view
 * the settlement terms configured by their super-merchant (bank) and request
 * changes to their settlement account.
 */
@RestController
@RequestMapping("/settlement-config")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('manage_settlement')")
public class SettlementConfigController {

    private final MerchantScope merchantScope;
    private final EntityManager entityManager;
    private final SettlementApprovalService settlementApprovalService;
    private final GrpcClient grpcClient;

    /**
     * Get the settlement configuration for the current merchant. Reads from
     * supermerchant.settlement_configs table.
     */
    @GetMapping
    @Transactional(readOnly = true)
    public ApiResponse<SettlementConfigResponse> get() {
        Long merchantId = merchantScope.merchantId();
        if (merchantId == null) {
            return ApiResponse.success(SettlementConfigResponse.builder().hasConfig(false).build());
        }

        try {
            Object[] row = (Object[]) entityManager
                    .createNativeQuery("SELECT settlement_type, settlement_time, settlement_days, "
                            + "account_name, bank_name, account_number, account_type, "
                            + "msc_pos_percentage, instant_transfer_fee, monthly_terminal_fee, pending_change "
                            + "FROM supermerchant.settlement_configs WHERE user_id = :userId")
                    .setParameter("userId", merchantId).getSingleResult();

            return ApiResponse.success(SettlementConfigResponse.builder().settlementType(str(row[0]))
                    .settlementTime(str(row[1])).settlementDays(str(row[2])).accountName(str(row[3]))
                    .bankName(str(row[4])).accountNumber(str(row[5])).accountType(str(row[6]))
                    .mscPosPercentage(decimal(row[7])).instantTransferFee(decimal(row[8]))
                    .monthlyTerminalFee(decimal(row[9])).pendingChange(str(row[10])).hasConfig(true).build());
        } catch (jakarta.persistence.NoResultException e) {
            // No config exists — return empty response
            return ApiResponse.success(SettlementConfigResponse.builder().hasConfig(false).build());
        }
    }

    /**
     * Get all settlement config approval requests for the current merchant. Returns
     * pending and historical requests ordered by creation date descending.
     */
    @GetMapping("/requests")
    @Transactional(readOnly = true)
    public ApiResponse<List<ApprovalRequestResponse>> getRequests() {
        Long merchantId = merchantScope.merchantId();
        if (merchantId == null) {
            return ApiResponse.error(401, "Not authenticated");
        }

        List<ApprovalRequestResponse> requests = settlementApprovalService.getApprovalRequests(merchantId);
        return ApiResponse.success(requests);
    }

    /**
     * Request a settlement account change. Creates a pending approval that the
     * super-merchant (bank) must approve.
     */
    @PostMapping("/request-account")
    @Transactional
    public ApiResponse<Map<String, Object>> requestAccountChange(
            @RequestBody @Valid SettlementAccountRequestDto request) {
        Long merchantId = merchantScope.merchantId();
        if (merchantId == null) {
            return ApiResponse.error(401, "Not authenticated");
        }

        // Get merchant name and email
        Map<String, String> merchantInfo = settlementApprovalService.getMerchantInfo(merchantId);
        String merchantName = merchantInfo.get("name");
        String merchantEmail = merchantInfo.get("email");

        // Check for existing pending request
        if (settlementApprovalService.hasPendingAccountRequest(merchantId)) {
            return ApiResponse.error(409, "A pending account change request already exists");
        }

        // Submit the request
        Long approvalId = settlementApprovalService.submitAccountChangeRequest(merchantId, request, merchantName,
                merchantEmail);

        if (approvalId == null) {
            return ApiResponse.error(400,
                    "Unable to submit account change request. Please ensure you have an assigned TID.");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("approval_id", approvalId);
        return ApiResponse.success(data, "Settlement account change request submitted for approval");
    }

    private static String str(Object o) {
        return o != null ? o.toString() : null;
    }

    private static BigDecimal decimal(Object o) {
        if (o == null)
            return null;
        if (o instanceof BigDecimal)
            return (BigDecimal) o;
        return new BigDecimal(o.toString());
    }

    // ── Bank code to name mapping ──────────────────────────────────────────────

    private static final Map<String, String> BANK_NAMES = Map.ofEntries(Map.entry("044", "Access Bank"),
            Map.entry("023", "Citibank"), Map.entry("050", "Ecobank"), Map.entry("084", "Enterprise Bank"),
            Map.entry("070", "Fidelity Bank"), Map.entry("011", "First Bank"), Map.entry("214", "FCMB"),
            Map.entry("058", "GTBank"), Map.entry("030", "Heritage Bank"), Map.entry("301", "Jaiz Bank"),
            Map.entry("082", "Keystone Bank"), Map.entry("526", "Parallex Bank"), Map.entry("076", "Polaris Bank"),
            Map.entry("101", "Providus Bank"), Map.entry("221", "Stanbic IBTC"), Map.entry("068", "Standard Chartered"),
            Map.entry("232", "Sterling Bank"), Map.entry("100", "Suntrust Bank"), Map.entry("032", "Union Bank"),
            Map.entry("033", "United Bank for Africa"), Map.entry("215", "Unity Bank"), Map.entry("035", "Wema Bank"),
            Map.entry("057", "Zenith Bank"), Map.entry("103", "Globus Bank"), Map.entry("102", "Titan Trust Bank"),
            Map.entry("120001", "9PSB"));

    private static String bankName(String code) {
        return BANK_NAMES.getOrDefault(code, code);
    }

    // ── Settlement Accounts Pool ───────────────────────────────────────────────

    /**
     * Get all settlement accounts for the current merchant. Reads directly from
     * tms-config via gRPC for real-time data.
     */
    @GetMapping("/accounts")
    public ApiResponse<List<SettlementAccountDto>> getAccounts() {
        Long merchantId = merchantScope.merchantId();
        if (merchantId == null) {
            return ApiResponse.success(List.of());
        }

        List<Map<String, Object>> grpcAccounts = grpcClient.listMerchantSettlementAccounts(merchantId);

        List<SettlementAccountDto> accounts = new ArrayList<>();
        for (Map<String, Object> acct : grpcAccounts) {
            String code = str(acct.get("bank_code"));
            accounts.add(SettlementAccountDto.builder().id(longVal(acct.get("id")))
                    .accountNumber(str(acct.get("account_number"))).bankCode(code).bankName(bankName(code))
                    .accountName(str(acct.get("account_name"))).label(str(acct.get("label")))
                    .isDefault(boolVal(acct.get("is_default"))).status(str(acct.get("status"))).createdAt(null) // gRPC
                                                                                                                // doesn't
                                                                                                                // return
                                                                                                                // created_at
                    .build());
        }

        return ApiResponse.success(accounts);
    }

    private static Long longVal(Object o) {
        if (o == null)
            return null;
        if (o instanceof Number)
            return ((Number) o).longValue();
        return Long.parseLong(o.toString());
    }

    private static boolean boolVal(Object o) {
        if (o == null)
            return false;
        if (o instanceof Boolean)
            return (Boolean) o;
        return Boolean.parseBoolean(o.toString());
    }

    /**
     * Convert a date/time value from native query to Instant. Handles both
     * java.sql.Timestamp and java.time.LocalDateTime depending on JDBC driver.
     */
    private static Instant toInstant(Object value) {
        if (value == null)
            return null;
        if (value instanceof Timestamp ts)
            return ts.toInstant();
        if (value instanceof LocalDateTime ldt)
            return ldt.toInstant(ZoneOffset.UTC);
        return null;
    }
}

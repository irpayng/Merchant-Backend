package com.tms.report.modules.settlement.controller;

import com.tms.report.core.dto.ApiResponse;
import com.tms.report.core.security.MerchantScope;
import com.tms.report.modules.settlement.dto.SettlementAccountRequestDto;
import com.tms.report.modules.settlement.dto.SettlementConfigResponse;
import com.tms.report.modules.settlement.service.SettlementApprovalService;
import jakarta.persistence.EntityManager;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
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
}

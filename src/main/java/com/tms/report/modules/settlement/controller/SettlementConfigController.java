package com.tms.report.modules.settlement.controller;

import com.tms.report.core.dto.ApiResponse;
import com.tms.report.core.security.MerchantScope;
import com.tms.report.modules.settlement.dto.SettlementConfigResponse;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only settlement configuration endpoint for merchant dashboard. Merchants
 * can view the settlement terms configured by their super-merchant (bank).
 */
@RestController
@RequestMapping("/settlement-config")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('manage_settlement')")
public class SettlementConfigController {

    private final MerchantScope merchantScope;
    private final EntityManager entityManager;

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

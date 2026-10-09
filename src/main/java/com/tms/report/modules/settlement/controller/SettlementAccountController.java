package com.tms.report.modules.settlement.controller;

import com.tms.report.core.dto.ApiResponse;
import com.tms.report.core.security.MerchantScope;
import com.tms.report.modules.settlement.dto.SettlementAccountResponse;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Settlement account pool endpoint for merchant dashboard. Returns the
 * merchant's active settlement accounts for branch/TID assignment.
 */
@RestController
@RequestMapping("/settlement-accounts")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('manage_settlement')")
public class SettlementAccountController {

    private final MerchantScope merchantScope;
    private final EntityManager entityManager;

    /**
     * List all active settlement accounts for the current merchant. Used by branch
     * and TID settlement assignment UI to populate the account dropdown.
     */
    @GetMapping
    @Transactional(readOnly = true)
    public ApiResponse<List<SettlementAccountResponse>> list() {
        Long merchantId = merchantScope.merchantId();
        if (merchantId == null) {
            return ApiResponse.success(List.of());
        }

        String sql = """
                SELECT sa.id, sa.account_number, sa.account_name, sa.bank_code,
                       (SELECT name FROM banks WHERE code = sa.bank_code LIMIT 1) as bank_name,
                       sa.label, sa.is_default, sa.status
                FROM merchant_settlement_accounts sa
                WHERE sa.user_id = :merchantId AND sa.status = 'active'
                ORDER BY sa.is_default DESC, sa.label ASC, sa.account_name ASC
                """;

        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(sql).setParameter("merchantId", merchantId)
                .getResultList();

        List<SettlementAccountResponse> accounts = new ArrayList<>();
        for (Object[] row : rows) {
            accounts.add(SettlementAccountResponse.builder().id(longVal(row[0])).accountNumber(str(row[1]))
                    .accountName(str(row[2])).bankCode(str(row[3])).bankName(str(row[4])).label(str(row[5]))
                    .isDefault(boolVal(row[6])).status(str(row[7])).build());
        }

        return ApiResponse.success(accounts);
    }

    private static String str(Object o) {
        return o != null ? o.toString() : null;
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
}

package com.tms.report.modules.settlement.controller;

import com.tms.report.core.dto.ApiResponse;
import com.tms.report.core.security.MerchantScope;
import com.tms.report.modules.grpc.service.GrpcClient;
import com.tms.report.modules.settlement.dto.SettlementAccountResponse;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Settlement account pool endpoint for merchant dashboard. Returns the
 * merchant's active settlement accounts for branch/TID assignment.
 *
 * <p>
 * Settlement accounts are stored in tms-config (the central config service),
 * accessed via gRPC. The super-merchant portal creates these accounts; the
 * merchant dashboard reads them for branch assignment.
 * </p>
 */
@RestController
@RequestMapping("/settlement-accounts")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('manage_settlement')")
public class SettlementAccountController {

    private final MerchantScope merchantScope;
    private final GrpcClient grpcClient;

    /**
     * List all active settlement accounts for the current merchant. Used by branch
     * and TID settlement assignment UI to populate the account dropdown.
     */
    @GetMapping
    public ApiResponse<List<SettlementAccountResponse>> list() {
        Long merchantId = merchantScope.merchantId();
        if (merchantId == null) {
            return ApiResponse.success(List.of());
        }

        List<Map<String, Object>> accounts = grpcClient.listMerchantSettlementAccounts(merchantId);

        List<SettlementAccountResponse> response = accounts.stream()
                .map(acct -> SettlementAccountResponse.builder().id(longVal(acct.get("id")))
                        .accountNumber(str(acct.get("account_number"))).accountName(str(acct.get("account_name")))
                        .bankCode(str(acct.get("bank_code"))).bankName(null).label(str(acct.get("label")))
                        .isDefault(boolVal(acct.get("is_default"))).status(str(acct.get("status"))).build())
                .collect(Collectors.toList());

        return ApiResponse.success(response);
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

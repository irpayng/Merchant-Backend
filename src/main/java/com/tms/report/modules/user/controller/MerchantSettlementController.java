package com.tms.report.modules.user.controller;

import com.tms.report.core.dto.ApiResponse;
import com.tms.report.core.security.TenantScope;
import com.tms.report.modules.activity.annotation.LogActivity;
import com.tms.report.modules.grpc.service.GrpcClient;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bank-facing instant-settlement + settlement-window management for a single
 * merchant. A bank enrolls its merchant for instant settlement and sets the
 * fixed clock-time windows and destination account; the settlement service then
 * sweeps the merchant's wallet to that account at each window.
 *
 * <p>
 * Tenant-scoped: a bank admin can only touch its own direct merchants (those
 * owning a TID stamped with the bank's code), and the destination bank is
 * forced to the bank's own institution — funds can only ever land at the
 * super-merchant bank, with a beneficiary name the sweep verifies against the
 * merchant name.
 */
@RestController
@RequestMapping("/merchants")
@RequiredArgsConstructor
public class MerchantSettlementController {

    private final GrpcClient grpcClient;
    private final TenantScope tenantScope;
    private final EntityManager entityManager;

    @GetMapping("/{userId}/settlement")
    @Transactional(readOnly = true)
    public ApiResponse<Map<String, Object>> get(@PathVariable Long userId) {
        assertInScope(userId);
        return ApiResponse.success(grpcClient.checkInstantSettlement(userId));
    }

    @LogActivity(action = "settlement", description = "{admin} configured settlement for merchant {userId}")
    @PostMapping("/{userId}/settlement")
    @Transactional
    public ApiResponse<Map<String, Object>> configure(@PathVariable Long userId,
            @RequestBody Map<String, Object> body) {
        assertInScope(userId);

        Map<String, Object> data = new LinkedHashMap<>(body != null ? body : Map.of());
        data.put("user_id", userId);
        // A bank can only ever settle to its own institution — force the
        // destination bank to the tenant bank for non-global admins.
        if (!tenantScope.isGlobal()) {
            data.put("destination_bank_code", tenantScope.bankCode());
        }

        boolean active = Boolean.TRUE.equals(grpcClient.checkInstantSettlement(userId).get("active"));
        Map<String, Object> result = active
                ? grpcClient.updateSettlementWindow(data)
                : grpcClient.enrollInstantSettlement(data);
        return ApiResponse.success(result);
    }

    /** A non-global admin may only manage merchants that own a TID for its bank. */
    private void assertInScope(Long userId) {
        if (tenantScope.isGlobal()) {
            return;
        }
        String bank = tenantScope.bankCode();
        boolean ok = bank != null && !bank.isBlank()
                && ((Number) entityManager
                        .createNativeQuery("SELECT COUNT(*) FROM tids WHERE bank_code = :bank AND user_id = :uid")
                        .setParameter("bank", bank).setParameter("uid", userId).getSingleResult()).longValue() > 0;
        if (!ok) {
            throw new EntityNotFoundException("Merchant not found");
        }
    }
}

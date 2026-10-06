package com.tms.report.modules.settings.controller;

import com.tms.report.core.response.ApiResponse;
import com.tms.report.core.security.MerchantUser;
import com.tms.report.modules.grpc.service.GrpcClient;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Merchant-scoped settings endpoints. These allow merchants to toggle
 * preferences that affect their POS devices and dashboard behavior.
 *
 * <p>
 * Distinct from the admin {@code SettingController} — these are per-merchant
 * settings stored in tms-user's {@code user_preferences} table, not global
 * system configurations.
 */
@RestController
@RequestMapping("/merchant-settings")
public class MerchantSettingsController {

    private final GrpcClient grpcClient;

    public MerchantSettingsController(GrpcClient grpcClient) {
        this.grpcClient = grpcClient;
    }

    /**
     * GET /merchant-settings/receipt-reprint-guard — Check if receipt reprint guard
     * is enabled for the authenticated merchant.
     */
    @GetMapping("/receipt-reprint-guard")
    public ApiResponse<Map<String, Object>> getReceiptReprintGuard(@AuthenticationPrincipal MerchantUser user) {
        Long merchantId = merchantId(user);
        Map<String, Object> pref = grpcClient.getUserPreference(merchantId, "receipt_reprint_guard");
        boolean enabled = Boolean.TRUE.equals(pref.get("found")) && "true".equalsIgnoreCase((String) pref.get("value"));
        return ApiResponse.success(Map.of("enabled", enabled));
    }

    /**
     * PUT /merchant-settings/receipt-reprint-guard — Enable or disable the receipt
     * reprint guard for the authenticated merchant.
     */
    @PutMapping("/receipt-reprint-guard")
    public ApiResponse<Map<String, Object>> setReceiptReprintGuard(@AuthenticationPrincipal MerchantUser user,
            @RequestBody Map<String, Object> body) {
        Long merchantId = merchantId(user);
        Boolean enabled = (Boolean) body.get("enabled");
        if (enabled == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "enabled field is required");
        }
        grpcClient.setUserPreference(merchantId, "receipt_reprint_guard", enabled ? "true" : "false");
        return ApiResponse.success(Map.of("enabled", enabled, "message", "Receipt reprint guard updated"));
    }

    private Long merchantId(MerchantUser user) {
        if (user == null || user.getMerchantId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Merchant authentication required");
        }
        return user.getMerchantId();
    }
}

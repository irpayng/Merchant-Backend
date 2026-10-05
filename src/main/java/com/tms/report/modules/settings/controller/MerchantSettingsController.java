package com.tms.report.modules.settings.controller;

import com.tms.report.core.dto.ApiResponse;
import com.tms.report.core.exception.AppException;
import com.tms.report.core.security.MerchantScope;
import com.tms.report.modules.grpc.service.GrpcClient;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * Merchant settings endpoints for preferences that live in tms-user. These are
 * merchant-specific settings (keyed by merchant_id) rather than the global
 * admin settings in {@code SettingController}.
 */
@RestController
@RequestMapping("/merchant-settings")
@RequiredArgsConstructor
public class MerchantSettingsController {

    private final MerchantScope merchantScope;
    private final GrpcClient grpcClient;

    private Long merchantId() {
        Long id = merchantScope.merchantId();
        if (id == null) {
            throw new AppException("Not authenticated", HttpStatus.UNAUTHORIZED);
        }
        return id;
    }

    /**
     * Get the receipt reprint guard setting for the authenticated merchant. Returns
     * enabled=true only if the preference exists and equals 'true'.
     */
    @GetMapping("/receipt-reprint-guard")
    public ApiResponse<Map<String, Object>> getReceiptReprintGuard() {
        Long id = merchantId();
        Map<String, Object> pref = grpcClient.getUserPreference(id, "receipt_reprint_guard");
        boolean found = Boolean.TRUE.equals(pref.get("found"));
        String value = (String) pref.get("value");
        boolean enabled = found && "true".equals(value);
        return ApiResponse.success(Map.of("enabled", enabled));
    }

    /**
     * Update the receipt reprint guard setting for the authenticated merchant.
     */
    @PutMapping("/receipt-reprint-guard")
    public ApiResponse<Map<String, Object>> setReceiptReprintGuard(@RequestBody Map<String, Object> body) {
        Long id = merchantId();
        Object enabledObj = body.get("enabled");
        boolean enabled = Boolean.TRUE.equals(enabledObj) || "true".equals(String.valueOf(enabledObj));
        grpcClient.setUserPreference(id, "receipt_reprint_guard", enabled ? "true" : "false");
        return ApiResponse.success(Map.of("enabled", enabled, "message", "Receipt reprint guard updated"));
    }
}

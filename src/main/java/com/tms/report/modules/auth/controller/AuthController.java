package com.tms.report.modules.auth.controller;

import com.tms.report.core.dto.ApiResponse;
import com.tms.report.modules.auth.dto.ActivateOtpRequest;
import com.tms.report.modules.auth.dto.ActivateRequest;
import com.tms.report.modules.auth.dto.ChangePasswordRequest;
import com.tms.report.modules.auth.dto.CrossLoginRequest;
import com.tms.report.modules.auth.dto.ForgotPasswordRequest;
import com.tms.report.modules.auth.dto.LoginRequest;
import com.tms.report.modules.auth.dto.LoginResponse;
import com.tms.report.modules.auth.dto.RequestActivationRequest;
import com.tms.report.modules.auth.dto.ResetPasswordRequest;
import com.tms.report.modules.auth.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.success(authService.login(request));
    }

    /**
     * Cross-system login for mobile-upgraded merchants. Validates credentials
     * against tms-user (the microservices user store) and issues a merchant
     * dashboard JWT.
     *
     * <p>
     * Use this endpoint when the merchant was originally onboarded via the mobile
     * app and later upgraded to merchant type. They can sign in with their existing
     * mobile app credentials (email/phone + password) instead of going through the
     * document-upload activation flow.
     */
    @PostMapping("/cross-login")
    public ApiResponse<LoginResponse> crossLogin(@Valid @RequestBody CrossLoginRequest request) {
        return ApiResponse.success(authService.crossLogin(request));
    }

    @GetMapping("/me")
    public ApiResponse<Object> me() {
        return ApiResponse.success(authService.me());
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        // JWT is stateless; client discards the token
        return ApiResponse.success(null);
    }

    @PostMapping("/change-password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(request);
        return ApiResponse.success(null);
    }

    @PostMapping("/forgot-password")
    public ApiResponse<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request);
        return ApiResponse.success(null);
    }

    @PostMapping("/reset-password")
    public ApiResponse<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ApiResponse.success(null);
    }

    // ── Account activation (upload-onboarded merchants set their password) ──

    /** Send an activation challenge (link or OTP) to a pending account. */
    @PostMapping("/request-activation")
    public ApiResponse<Void> requestActivation(@Valid @RequestBody RequestActivationRequest request) {
        authService.requestActivation(request);
        return ApiResponse.success(null);
    }

    /** Complete activation via the emailed link token. */
    @PostMapping("/activate")
    public ApiResponse<Void> activate(@Valid @RequestBody ActivateRequest request) {
        authService.activate(request);
        return ApiResponse.success(null);
    }

    /** Complete activation via the OTP channel. */
    @PostMapping("/activate-otp")
    public ApiResponse<Void> activateOtp(@Valid @RequestBody ActivateOtpRequest request) {
        authService.activateOtp(request);
        return ApiResponse.success(null);
    }
}

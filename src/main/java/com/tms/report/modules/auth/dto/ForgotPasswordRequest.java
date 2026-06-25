package com.tms.report.modules.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ForgotPasswordRequest {

    /**
     * Accepts either an email address or a phone number. The service detects the
     * type and sends the OTP via the appropriate channel.
     */
    @NotBlank
    private String identifier;
}

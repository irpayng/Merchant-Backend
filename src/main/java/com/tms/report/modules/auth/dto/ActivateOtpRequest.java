package com.tms.report.modules.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Completes activation via the OTP channel, setting the password. */
@Data
public class ActivateOtpRequest {

    @NotBlank
    private String identifier;

    @NotBlank
    private String otp;

    @NotBlank
    @Size(min = 8, message = "Password must be at least 8 characters")
    private String password;
}

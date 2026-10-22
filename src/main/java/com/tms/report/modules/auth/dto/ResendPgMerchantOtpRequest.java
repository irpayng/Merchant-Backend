package com.tms.report.modules.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload for resending email verification OTP to a PG merchant.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResendPgMerchantOtpRequest {

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private String email;
}

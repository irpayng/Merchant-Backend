package com.tms.report.modules.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload for cross-system login. Mobile-upgraded merchants can sign
 * into the merchant dashboard using their tms-user credentials (email or phone
 * + password).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CrossLoginRequest {

    /** Email or phone number */
    @NotBlank(message = "Email or phone number is required")
    private String identifier;

    @NotBlank(message = "Password is required")
    private String password;
}

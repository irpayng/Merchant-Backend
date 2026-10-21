package com.tms.report.modules.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload for registering a payment gateway merchant. PG merchants are
 * identified by user type {@code pg_merchant}, distinct from {@code merchant}
 * used by PTSP/agent banking.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisterPgMerchantRequest {

    /** Country code. Defaults to Nigeria ('NG'). */
    @Builder.Default
    private String country = "NG";

    @NotBlank(message = "Business name is required")
    private String businessName;

    /** Business type: 'starter' (unregistered) or 'registered' (CAC registered). */
    @NotBlank(message = "Business type is required")
    @Pattern(regexp = "^(starter|registered)$", message = "Business type must be 'starter' or 'registered'")
    private String businessType;

    @NotBlank(message = "First name is required")
    private String firstName;

    @NotBlank(message = "Last name is required")
    private String lastName;

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private String email;

    @NotBlank(message = "Phone number is required")
    private String phoneNumber;

    @NotBlank(message = "Password is required")
    @Size(min = 8, message = "Password must be at least 8 characters")
    private String password;
}

package com.tms.report.modules.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response payload for successful payment gateway merchant registration.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisterPgMerchantResponse {

    private Long userId;
    private String email;
    private String phoneNumber;
    private String businessName;
    private String businessType;
    private String userType;
    private String message;
}

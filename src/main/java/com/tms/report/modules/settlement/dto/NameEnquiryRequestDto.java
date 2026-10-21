package com.tms.report.modules.settlement.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for name enquiry from settlement account form.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NameEnquiryRequestDto {

    @NotBlank(message = "Account number is required")
    @Pattern(regexp = "\\d{10}", message = "Account number must be exactly 10 digits")
    @JsonProperty("account_number")
    private String accountNumber;

    @NotBlank(message = "Bank code is required")
    @JsonProperty("bank_code")
    private String bankCode;
}

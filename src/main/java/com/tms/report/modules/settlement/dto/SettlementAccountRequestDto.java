package com.tms.report.modules.settlement.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for merchant-initiated settlement account change requests.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SettlementAccountRequestDto {

    @JsonProperty("bank_code")
    private String bankCode;

    @NotBlank(message = "Bank name is required")
    @JsonProperty("bank_name")
    private String bankName;

    @NotBlank(message = "Account number is required")
    @Size(max = 10, message = "Account number must be at most 10 digits")
    @JsonProperty("account_number")
    private String accountNumber;

    @NotBlank(message = "Account name is required")
    @JsonProperty("account_name")
    private String accountName;
}

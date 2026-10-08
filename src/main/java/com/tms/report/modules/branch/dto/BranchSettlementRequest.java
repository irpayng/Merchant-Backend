package com.tms.report.modules.branch.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request DTO for updating branch settlement account.
 */
@Data
public class BranchSettlementRequest {

    @JsonProperty("account_number")
    @Size(min = 10, max = 10, message = "Account number must be 10 digits")
    @Pattern(regexp = "^\\d{10}$", message = "Account number must be 10 digits")
    private String accountNumber;

    @JsonProperty("account_name")
    @Size(max = 255, message = "Account name must not exceed 255 characters")
    private String accountName;

    @JsonProperty("bank_code")
    @Size(max = 10, message = "Bank code must not exceed 10 characters")
    private String bankCode;

    @JsonProperty("bank_name")
    @Size(max = 100, message = "Bank name must not exceed 100 characters")
    private String bankName;
}

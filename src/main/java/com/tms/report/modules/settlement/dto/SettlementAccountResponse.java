package com.tms.report.modules.settlement.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

/**
 * Settlement account DTO for merchant dashboard. Represents an account from the
 * merchant's settlement account pool.
 */
@Data
@Builder
public class SettlementAccountResponse {

    private Long id;

    @JsonProperty("account_number")
    private String accountNumber;

    @JsonProperty("account_name")
    private String accountName;

    @JsonProperty("bank_code")
    private String bankCode;

    @JsonProperty("bank_name")
    private String bankName;

    private String label;

    @JsonProperty("is_default")
    private boolean isDefault;

    private String status;
}

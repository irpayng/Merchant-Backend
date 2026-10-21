package com.tms.report.modules.settlement.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Settlement account in the merchant's account pool.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SettlementAccountDto {

    private Long id;

    @JsonProperty("account_number")
    private String accountNumber;

    @JsonProperty("bank_code")
    private String bankCode;

    @JsonProperty("bank_name")
    private String bankName;

    @JsonProperty("account_name")
    private String accountName;

    private String label;

    @JsonProperty("is_default")
    private boolean isDefault;

    private String status;

    @JsonProperty("created_at")
    private Instant createdAt;
}

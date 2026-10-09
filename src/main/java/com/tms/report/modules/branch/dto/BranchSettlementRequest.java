package com.tms.report.modules.branch.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Request DTO for assigning a settlement account to a branch. The account must
 * exist in the merchant's settlement account pool.
 */
@Data
public class BranchSettlementRequest {

    @JsonProperty("settlement_account_id")
    @NotNull(message = "Settlement account ID is required")
    private Long settlementAccountId;
}

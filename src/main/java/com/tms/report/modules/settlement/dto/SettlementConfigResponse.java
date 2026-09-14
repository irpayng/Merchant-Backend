package com.tms.report.modules.settlement.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Read-only settlement configuration response for merchants.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SettlementConfigResponse {

    // ── Settlement Schedule ──

    @JsonProperty("settlement_type")
    private String settlementType;

    @JsonProperty("settlement_time")
    private String settlementTime;

    @JsonProperty("settlement_days")
    private String settlementDays;

    // ── Settlement Account Details ──

    @JsonProperty("account_name")
    private String accountName;

    @JsonProperty("bank_name")
    private String bankName;

    @JsonProperty("account_number")
    private String accountNumber;

    @JsonProperty("account_type")
    private String accountType;

    // ── MSC Breakdown ──

    @JsonProperty("msc_pos_percentage")
    private BigDecimal mscPosPercentage;

    @JsonProperty("instant_transfer_fee")
    private BigDecimal instantTransferFee;

    @JsonProperty("monthly_terminal_fee")
    private BigDecimal monthlyTerminalFee;

    // ── Status ──

    @JsonProperty("pending_change")
    private String pendingChange;

    @JsonProperty("has_config")
    private boolean hasConfig;
}

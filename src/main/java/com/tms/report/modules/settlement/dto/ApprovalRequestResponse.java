package com.tms.report.modules.settlement.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for approval request listing. Used to display pending and historical
 * approval requests to merchants.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApprovalRequestResponse {

    private Long id;

    @JsonProperty("user_id")
    private Long userId;

    private String section;

    private String status;

    private String description;

    private String payload;

    @JsonProperty("reject_reason")
    private String rejectReason;

    @JsonProperty("created_at")
    private LocalDateTime createdAt;

    @JsonProperty("approved_at")
    private LocalDateTime approvedAt;

    @JsonProperty("rejected_at")
    private LocalDateTime rejectedAt;

    @JsonProperty("executed_at")
    private LocalDateTime executedAt;
}

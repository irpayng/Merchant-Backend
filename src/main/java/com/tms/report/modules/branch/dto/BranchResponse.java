package com.tms.report.modules.branch.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import lombok.Builder;
import lombok.Data;

/**
 * Branch response DTO for merchant dashboard.
 */
@Data
@Builder
public class BranchResponse {

    private Long id;

    @JsonProperty("branch_id")
    private String branchId;

    private String name;

    private String code;

    private String address;

    private String location;

    @JsonProperty("state_code")
    private String stateCode;

    @JsonProperty("lga_code")
    private String lgaCode;

    @JsonProperty("phone_number")
    private String phoneNumber;

    private String email;

    private String status;

    @JsonProperty("is_primary")
    private boolean isPrimary;

    private Integer terminals;

    @JsonProperty("total_volume")
    private Long totalVolume;

    @JsonProperty("created_at")
    private Instant createdAt;

    @JsonProperty("updated_at")
    private Instant updatedAt;
}

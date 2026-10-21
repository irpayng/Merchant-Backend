package com.tms.report.modules.branch.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request DTO for creating a branch.
 */
@Data
public class BranchCreateRequest {

    @NotBlank(message = "Branch name is required")
    @Size(max = 255, message = "Branch name must be less than 255 characters")
    private String name;

    @Size(max = 20, message = "Branch code must be less than 20 characters")
    private String code;

    private String address;

    private String location;

    @JsonProperty("state_code")
    @Size(max = 10, message = "State code must be less than 10 characters")
    private String stateCode;

    @JsonProperty("lga_code")
    @Size(max = 20, message = "LGA code must be less than 20 characters")
    private String lgaCode;

    @JsonProperty("phone_number")
    @Size(max = 20, message = "Phone number must be less than 20 characters")
    private String phoneNumber;

    @Size(max = 255, message = "Email must be less than 255 characters")
    private String email;

    @JsonProperty("is_primary")
    private Boolean isPrimary;
}

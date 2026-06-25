package com.tms.report.modules.transaction.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserRef {
    private Long id;
    private String email;
    @JsonProperty("onboarding_id")
    private Long onboardingId;
    private String name;
    private ProfileRef profile;
}

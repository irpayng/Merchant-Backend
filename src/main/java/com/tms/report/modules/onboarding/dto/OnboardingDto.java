package com.tms.report.modules.onboarding.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Row shape returned by {@code GET /onboardings}. Represents an in-progress (or
 * completed) registration from the onboarding-service {@code onboardings}
 * table, which is replicated into the report database.
 *
 * <p>
 * The list view intentionally omits the raw {@code bvn} — see
 * {@code OnboardingService.showDetail} for the full record an admin sees when
 * opening a single onboarding.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.ALWAYS)
public class OnboardingDto {

    @JsonProperty("id")
    private Long id;

    @JsonProperty("reference")
    private String reference;

    @JsonProperty("name")
    private String name;

    @JsonProperty("email")
    private String email;

    @JsonProperty("phone_number")
    private String phoneNumber;

    /**
     * The phone number returned by the BVN lookup (may differ from the entered
     * one).
     */
    @JsonProperty("bvn_phone_number")
    private String bvnPhoneNumber;

    @JsonProperty("is_bvn_phone_number")
    private Boolean isBvnPhoneNumber;

    @JsonProperty("bvn_is_validated")
    private Boolean bvnIsValidated;

    @JsonProperty("phone_number_is_validated")
    private Boolean phoneNumberIsValidated;

    @JsonProperty("email_is_validated")
    private Boolean emailIsValidated;

    @JsonProperty("liveliness_is_validated")
    private Boolean livelinessIsValidated;

    /**
     * Derived rollup of the four validation flags into a single coarse status:
     * {@code completed}, {@code in_progress}, or {@code started}. Used for the
     * status column and the stats cards.
     */
    @JsonProperty("status")
    private String status;

    @JsonProperty("created_at")
    private LocalDateTime createdAt;
}

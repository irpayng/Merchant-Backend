package com.tms.report.modules.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Kicks off account activation for an upload-onboarded (or invited) merchant
 * login. {@code identifier} is the email or phone captured at onboarding;
 * {@code channel} selects the delivery method.
 */
@Data
public class RequestActivationRequest {

    @NotBlank
    private String identifier;

    /** link | otp */
    @NotBlank
    private String channel;
}

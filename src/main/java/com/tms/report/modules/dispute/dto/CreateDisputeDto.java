package com.tms.report.modules.dispute.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateDisputeDto {

    private String transactionReference;

    @NotBlank(message = "The subject field is required.")
    private String subject;

    private String message;
}

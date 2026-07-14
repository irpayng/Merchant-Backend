package com.tms.report.modules.dispute.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AddConversationDto {

    @NotBlank(message = "The message field is required.")
    private String message;
}

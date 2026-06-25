package com.tms.report.modules.activity.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ActivityDto {

    private Long id;
    private String action;
    private String description;
    private String adminName;
    private String actionableType;
    private Long actionableId;
    private LocalDateTime createdAt;
}

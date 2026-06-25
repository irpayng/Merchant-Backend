package com.tms.report.modules.admin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AdminDto {

    private Long id;
    private String name;
    private String email;
    private String phoneNumber;
    private List<RoleDto> roles;
    private String status;
    private String blockedReason;
    private LocalDateTime createdAt;
}

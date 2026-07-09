package com.tms.report.modules.role.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AssignRoleRequest {

    @NotNull
    private Long roleId;
}

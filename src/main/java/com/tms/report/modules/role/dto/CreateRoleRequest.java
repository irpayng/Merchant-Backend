package com.tms.report.modules.role.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Set;
import lombok.Data;

@Data
public class CreateRoleRequest {

    @NotBlank
    @Size(max = 100)
    private String name;

    @NotBlank
    @Size(max = 100)
    @Pattern(regexp = "^[a-z][a-z0-9_-]*$", message = "Slug must be lowercase alphanumeric with hyphens/underscores")
    private String slug;

    @Size(max = 500)
    private String description;

    /** Privilege IDs to assign to this role. */
    private Set<Long> privilegeIds;
}

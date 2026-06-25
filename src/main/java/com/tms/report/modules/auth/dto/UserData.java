package com.tms.report.modules.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserData {
    private String name;
    private String email;

    @JsonProperty("email_is_verified")
    private boolean emailIsVerified;

    private List<RoleData> roles;
}

package com.tms.report.modules.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
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
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserData {
    private String name;
    private String email;

    /** owner | cashier (legacy string role) */
    private String role;

    @JsonProperty("merchant_id")
    private Long merchantId;

    /** Set only for a cashier locked to a single terminal. */
    @JsonProperty("terminal_id")
    private Long terminalId;

    @JsonProperty("email_is_verified")
    private boolean emailIsVerified;

    /** Privilege codes granted to this user via their assigned role. */
    private List<String> privileges;
}

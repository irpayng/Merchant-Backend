package com.tms.report.modules.admin.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import lombok.Data;

@Data
public class CreateAdminRequest {

    @NotBlank
    private String name;

    @NotBlank
    @Email
    private String email;

    private String phoneNumber;

    @NotBlank
    private String password;

    /** Optional tenant assignment — the bank this portal user belongs to. */
    private String bankCode;

    private List<String> roles;
}

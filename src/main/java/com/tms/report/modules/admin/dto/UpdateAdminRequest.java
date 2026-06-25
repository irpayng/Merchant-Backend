package com.tms.report.modules.admin.dto;

import java.util.List;
import lombok.Data;

@Data
public class UpdateAdminRequest {

    private String name;
    private String email;
    private String phoneNumber;
    private String password;
    private String bankCode;
    private List<String> roles;
}

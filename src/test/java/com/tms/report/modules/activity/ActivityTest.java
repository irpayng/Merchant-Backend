package com.tms.report.modules.activity;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tms.report.BaseIntegrationTest;
import com.tms.report.core.security.AdminDetails;
import org.junit.jupiter.api.Test;

class ActivityTest extends BaseIntegrationTest {

    private AdminDetails adminDetails() {
        var admin = adminRepository.findByEmail("admin@irpay.ng").orElseThrow();
        return new AdminDetails(admin);
    }

    @Test
    void canListActivities() throws Exception {
        mockMvc.perform(get("/activities").with(user(adminDetails()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void activitiesRequireAuthentication() throws Exception {
        mockMvc.perform(get("/activities")).andExpect(status().isUnauthorized());
    }
}

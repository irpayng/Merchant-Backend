package com.tms.report.modules.role;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tms.report.BaseIntegrationTest;
import com.tms.report.core.security.AdminDetails;
import org.junit.jupiter.api.Test;

class RoleTest extends BaseIntegrationTest {

    private AdminDetails adminDetails() {
        var admin = adminRepository.findByEmail("admin@irpay.ng").orElseThrow();
        return new AdminDetails(admin);
    }

    @Test
    void canListRoles() throws Exception {
        mockMvc.perform(get("/roles/all").with(user(adminDetails()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray()).andExpect(jsonPath("$.data[0].id").exists())
                .andExpect(jsonPath("$.data[0].name").exists()).andExpect(jsonPath("$.data[0].code").exists());
    }

    @Test
    void canListPrivileges() throws Exception {
        mockMvc.perform(get("/roles/privileges/all").with(user(adminDetails()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }
}

package com.tms.report.modules.user;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tms.report.BaseIntegrationTest;
import com.tms.report.core.security.AdminDetails;
import org.junit.jupiter.api.Test;

class UserTest extends BaseIntegrationTest {

    private AdminDetails adminDetails() {
        var admin = adminRepository.findByEmail("admin@irpay.ng").orElseThrow();
        return new AdminDetails(admin);
    }

    @Test
    void canListUsers() throws Exception {
        mockMvc.perform(get("/users").with(user(adminDetails()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray()).andExpect(jsonPath("$.meta").exists());
    }

    @Test
    void canShowUser() throws Exception {
        mockMvc.perform(get("/users/1").with(user(adminDetails()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(1)).andExpect(jsonPath("$.data.email").value("user1@test.com"));
    }

    @Test
    void canGetUserSummary() throws Exception {
        mockMvc.perform(get("/users/summary").with(user(adminDetails()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").isNumber()).andExpect(jsonPath("$.data.active").isNumber())
                .andExpect(jsonPath("$.data.inactive").isNumber());
    }

    @Test
    void canGetUserFilters() throws Exception {
        mockMvc.perform(get("/users/filters").with(user(adminDetails()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tiers").isArray()).andExpect(jsonPath("$.data.pnd").isArray());
    }

    @Test
    void canFilterUsersByType() throws Exception {
        mockMvc.perform(get("/users?type=agent").with(user(adminDetails()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void canSearchUsers() throws Exception {
        mockMvc.perform(get("/users?search=user1").with(user(adminDetails()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void usersRequireAuthentication() throws Exception {
        mockMvc.perform(get("/users")).andExpect(status().isUnauthorized());
    }
}

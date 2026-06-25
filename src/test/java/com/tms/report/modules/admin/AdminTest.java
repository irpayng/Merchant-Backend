package com.tms.report.modules.admin;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tms.report.BaseIntegrationTest;
import com.tms.report.core.security.AdminDetails;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class AdminTest extends BaseIntegrationTest {

    private AdminDetails adminDetails() {
        var admin = adminRepository.findByEmail("admin@irpay.ng").orElseThrow();
        return new AdminDetails(admin);
    }

    @Test
    void canListAdmins() throws Exception {
        mockMvc.perform(get("/admins").with(user(adminDetails()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void canListAdminsWithPagination() throws Exception {
        mockMvc.perform(get("/admins?page=1&limit=10").with(user(adminDetails()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.current_page").value(1)).andExpect(jsonPath("$.meta.per_page").value(10));
    }

    @Test
    void canShowAdmin() throws Exception {
        mockMvc.perform(get("/admins/1").with(user(adminDetails()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(1)).andExpect(jsonPath("$.data.email").value("admin@irpay.ng"));
    }

    @Test
    void canCreateAdmin() throws Exception {
        String body = """
                {
                    "name": "New Admin",
                    "email": "newadmin@test.com",
                    "password": "password123",
                    "roles": ["2"]
                }
                """;
        mockMvc.perform(
                post("/admins").with(user(adminDetails())).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.email").value("newadmin@test.com"))
                .andExpect(jsonPath("$.data.name").value("New Admin"));
    }

    @Test
    void canUpdateAdmin() throws Exception {
        String body = """
                {"name": "Updated Admin"}
                """;
        mockMvc.perform(
                put("/admins/1").with(user(adminDetails())).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("Updated Admin"));
    }

    @Test
    void canBlockAdmin() throws Exception {
        String body = """
                {"reason": "Test block"}
                """;
        mockMvc.perform(post("/admins/1/block").with(user(adminDetails())).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("blocked"));
    }

    @Test
    void canUnblockAdmin() throws Exception {
        mockMvc.perform(post("/admins/1/unblock").with(user(adminDetails()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("active"));
    }

    @Test
    void adminsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/admins")).andExpect(status().isUnauthorized());
    }
}

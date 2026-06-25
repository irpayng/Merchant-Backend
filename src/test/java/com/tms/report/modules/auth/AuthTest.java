package com.tms.report.modules.auth;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tms.report.BaseIntegrationTest;
import com.tms.report.core.security.AdminDetails;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class AuthTest extends BaseIntegrationTest {

    @Test
    void loginWithInvalidCredentialsReturns401() throws Exception {
        String body = """
                {"email":"invalid@example.com","password":"wrongpassword"}
                """;
        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginWithMissingFieldsReturns422() throws Exception {
        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void meRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void meReturnsAdminWhenAuthenticated() throws Exception {
        authenticateAsAdmin();
        var admin = adminRepository.findByEmail("admin@irpay.ng").orElseThrow();
        var details = new AdminDetails(admin);

        mockMvc.perform(get("/auth/me").with(user(details))).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void logoutReturnsSuccess() throws Exception {
        authenticateAsAdmin();
        var admin = adminRepository.findByEmail("admin@irpay.ng").orElseThrow();
        var details = new AdminDetails(admin);

        mockMvc.perform(post("/auth/logout").with(user(details))).andExpect(status().isOk());
    }
}

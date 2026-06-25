package com.tms.report;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

class HealthCheckTest extends BaseIntegrationTest {

    @Test
    void healthEndpointReturnsOk() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/health")).andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.status").value("ok"));
    }
}

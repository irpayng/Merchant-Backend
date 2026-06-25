package com.tms.report.modules.transaction;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tms.report.BaseIntegrationTest;
import com.tms.report.core.security.AdminDetails;
import org.junit.jupiter.api.Test;

class TransactionTest extends BaseIntegrationTest {

    private AdminDetails adminDetails() {
        var admin = adminRepository.findByEmail("admin@irpay.ng").orElseThrow();
        return new AdminDetails(admin);
    }

    @Test
    void canListTransactions() throws Exception {
        mockMvc.perform(get("/transactions?page=1&limit=10").with(user(adminDetails()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray()).andExpect(jsonPath("$.meta.current_page").value(1));
    }

    @Test
    void canShowTransactionById() throws Exception {
        mockMvc.perform(get("/transactions/1").with(user(adminDetails()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(1)).andExpect(jsonPath("$.data.reference").value("txn_ref_1"));
    }

    @Test
    void canShowTransactionByReference() throws Exception {
        mockMvc.perform(get("/transactions/txn_ref_2").with(user(adminDetails()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reference").value("txn_ref_2"));
    }

    @Test
    void canGetTransactionFilters() throws Exception {
        mockMvc.perform(get("/transactions/filters").with(user(adminDetails()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.statuses").isArray()).andExpect(jsonPath("$.data.products").isArray())
                .andExpect(jsonPath("$.data.providers").isArray()).andExpect(jsonPath("$.data.channels").isArray())
                .andExpect(jsonPath("$.data.payment_methods").isArray());
    }

    @Test
    void canGetTransactionSummary() throws Exception {
        mockMvc.perform(get("/transactions/get-summary").with(user(adminDetails()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").exists());
    }

    @Test
    void canGetChannelChart() throws Exception {
        mockMvc.perform(get("/transactions/charts/channels").with(user(adminDetails()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.categories").isArray()).andExpect(jsonPath("$.data.series").isArray());
    }

    @Test
    void canGetProductChart() throws Exception {
        mockMvc.perform(get("/transactions/charts/products").with(user(adminDetails()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.categories").isArray()).andExpect(jsonPath("$.data.series").isArray());
    }

    @Test
    void canGetPaymentMethodChart() throws Exception {
        mockMvc.perform(get("/transactions/charts/payment-methods").with(user(adminDetails())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.categories").isArray())
                .andExpect(jsonPath("$.data.series").isArray());
    }

    @Test
    void canGetTimeVolumeChart() throws Exception {
        mockMvc.perform(get("/transactions/charts/time-volume").with(user(adminDetails()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.categories").isArray()).andExpect(jsonPath("$.data.series").isArray());
    }

    @Test
    void canFilterTransactionsByStatus() throws Exception {
        mockMvc.perform(get("/transactions?status_code=completed").with(user(adminDetails())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void transactionsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/transactions")).andExpect(status().isUnauthorized());
    }
}

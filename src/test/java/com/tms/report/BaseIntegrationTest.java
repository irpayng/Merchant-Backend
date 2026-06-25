package com.tms.report;

import com.tms.report.core.security.AdminDetails;
import com.tms.report.modules.admin.model.Admin;
import com.tms.report.modules.admin.repository.AdminRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Base class for integration tests. Uses H2 in-memory DB with PostgreSQL mode,
 * seeded from schema.sql + data.sql.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class BaseIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected AdminRepository adminRepository;

    /**
     * Authenticate as the seeded test admin (admin@irpay.ng).
     */
    protected void authenticateAsAdmin() {
        Admin admin = adminRepository.findByEmail("admin@irpay.ng")
                .orElseThrow(() -> new RuntimeException("Seed admin not found"));
        AdminDetails details = new AdminDetails(admin);
        var auth = new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    protected String adminToken() {
        // For MockMvc we use SecurityContext directly, not JWT tokens.
        // This is a placeholder if needed for header-based tests.
        return "Bearer test-token";
    }
}

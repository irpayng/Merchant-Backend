package com.tms.report.core.command;

import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

/**
 * Creates admin-specific tables (admins, roles, privileges, join tables) on
 * startup. These tables are NOT replicated from tms-api — they are owned by
 * tms-report-java.
 *
 * Uses IF NOT EXISTS so it's safe to run repeatedly. Runs before AppSeedCommand
 * (Order 0 vs Order 1).
 *
 * Skipped in the "test" profile — the test schema.sql handles table creation
 * and data.sql handles seeding for H2 compatibility.
 */
@Slf4j
@Component
@Order(0)
@Profile("!test")
@RequiredArgsConstructor
public class AdminSchemaInitializer implements CommandLineRunner {

    private final DataSource dataSource;

    @Override
    public void run(String... args) {
        log.info("Ensuring admin tables exist...");
        var populator = new ResourceDatabasePopulator();
        populator.addScript(new ClassPathResource("db/admin-schema.sql"));
        populator.setContinueOnError(false);
        populator.execute(dataSource);
        log.info("Admin tables ready.");
    }
}

package com.tms.report;

import jakarta.annotation.PostConstruct;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.TimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
public class TmsReportApplication {

    private static final Logger log = LoggerFactory.getLogger(TmsReportApplication.class);

    /**
     * Business timezone. Empty (the default) means "inherit the JVM default", which
     * is driven by the {@code TZ} environment variable set on the container — the
     * single knob shared with every other service. Set this only to override that.
     *
     * <p>
     * This property used to exist in {@code application.properties} while the zone
     * was actually hardcoded below, so editing it did nothing. It is now the real
     * override.
     */
    @Value("${app.timezone:}")
    private String configuredTimezone;

    public static void main(String[] args) {
        SpringApplication.run(TmsReportApplication.class, args);
    }

    /**
     * Aligns the JVM default zone with the configured business zone.
     *
     * <p>
     * This matters more here than in the Quarkus services: this application writes
     * {@code timestamp without time zone} columns for its own admin tables
     * ({@code admins}, {@code roles}, {@code settings}, {@code products}, …), so
     * the JVM default zone decides the wall-clock value that lands in the database.
     * If it silently changes, those columns end up holding a mix of two zones with
     * nothing in the row to tell them apart.
     */
    @PostConstruct
    void setTimezone() {
        if (configuredTimezone == null || configuredTimezone.isBlank()) {
            log.info("Business timezone: inheriting JVM default {} (from the TZ environment variable)",
                    TimeZone.getDefault().getID());
            return;
        }
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of(configuredTimezone.trim())));
            log.info("Business timezone set to {} (from app.timezone)", TimeZone.getDefault().getID());
        } catch (DateTimeException e) {
            log.warn("Invalid app.timezone='{}' — keeping JVM default {}", configuredTimezone,
                    TimeZone.getDefault().getID());
        }
    }
}

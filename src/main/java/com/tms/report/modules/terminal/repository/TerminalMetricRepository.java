package com.tms.report.modules.terminal.repository;

import com.tms.report.modules.terminal.model.TerminalMetric;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TerminalMetricRepository extends JpaRepository<TerminalMetric, Long> {

    Optional<TerminalMetric> findFirstBySerialOrderByCreatedAtDesc(String serial);

    Page<TerminalMetric> findBySerialOrderByCreatedAtDesc(String serial, Pageable pageable);

    Page<TerminalMetric> findBySerialAndCreatedAtBetweenOrderByCreatedAtDesc(String serial, LocalDateTime from,
            LocalDateTime to, Pageable pageable);

    /**
     * Most recent snapshot per device. Used to render fleet-wide health views
     * without scanning the full history table.
     *
     * <p>
     * Implemented as a <b>loose index scan</b> (recursive skip-scan) over
     * {@code idx_terminal_metrics_serial_created (serial, created_at DESC)}: the
     * recursive CTE walks the index one distinct {@code serial} at a time, then a
     * {@code LATERAL} picks that serial's newest row with a single indexed
     * {@code ORDER BY created_at DESC LIMIT 1}. Cost scales with the number of
     * <em>devices</em> (~hundreds), not the number of <em>rows</em> (which grows
     * without bound on this append-only telemetry table).
     *
     * <p>
     * The previous {@code SELECT DISTINCT ON (serial) ... ORDER BY serial,
     * created_at} could not use the index (it projected non-indexed columns, so the
     * planner chose a full seq scan + on-disk sort of the whole table — ~1s and
     * climbing). The fleet-health summary derives all of its counts from this one
     * result set in memory (see the controller), so this is the single query that
     * backs that dashboard.
     */
    @Query(value = """
            WITH RECURSIVE serials AS (
                (SELECT serial FROM terminal_metrics ORDER BY serial LIMIT 1)
                UNION ALL
                SELECT (SELECT tm.serial FROM terminal_metrics tm
                        WHERE tm.serial > s.serial ORDER BY tm.serial LIMIT 1)
                FROM serials s WHERE s.serial IS NOT NULL
            )
            SELECT latest.*
            FROM serials s
            CROSS JOIN LATERAL (
                SELECT tm.* FROM terminal_metrics tm
                WHERE tm.serial = s.serial ORDER BY tm.created_at DESC LIMIT 1
            ) latest
            WHERE s.serial IS NOT NULL
            """, nativeQuery = true)
    List<TerminalMetric> findLatestPerSerial();
}

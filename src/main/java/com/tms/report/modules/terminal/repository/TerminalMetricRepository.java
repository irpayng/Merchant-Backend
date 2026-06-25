package com.tms.report.modules.terminal.repository;

import com.tms.report.modules.terminal.model.TerminalMetric;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TerminalMetricRepository extends JpaRepository<TerminalMetric, Long> {

    Optional<TerminalMetric> findFirstBySerialOrderByCreatedAtDesc(String serial);

    Page<TerminalMetric> findBySerialOrderByCreatedAtDesc(String serial, Pageable pageable);

    Page<TerminalMetric> findBySerialAndCreatedAtBetweenOrderByCreatedAtDesc(String serial, LocalDateTime from,
            LocalDateTime to, Pageable pageable);

    /**
     * Most recent snapshot per device. Used to render fleet-wide health views
     * without scanning the full history table.
     */
    @Query(value = """
            SELECT DISTINCT ON (serial) *
            FROM terminal_metrics
            ORDER BY serial, created_at DESC
            """, nativeQuery = true)
    List<TerminalMetric> findLatestPerSerial();

    /**
     * Count of devices whose latest battery reading is below the given threshold.
     */
    @Query(value = """
            SELECT COUNT(*) FROM (
              SELECT DISTINCT ON (serial) battery_pct
              FROM terminal_metrics
              ORDER BY serial, created_at DESC
            ) latest
            WHERE battery_pct IS NOT NULL AND battery_pct < :threshold
            """, nativeQuery = true)
    long countLowBattery(@Param("threshold") int threshold);

    /**
     * Count of devices whose latest printer status is non-zero (printer not ready).
     */
    @Query(value = """
            SELECT COUNT(*) FROM (
              SELECT DISTINCT ON (serial) printer_status
              FROM terminal_metrics
              ORDER BY serial, created_at DESC
            ) latest
            WHERE printer_status IS NOT NULL AND printer_status <> 0
            """, nativeQuery = true)
    long countPrinterNotReady();

    /** Count of devices that haven't reported in the given window. */
    @Query(value = """
            SELECT COUNT(*) FROM (
              SELECT DISTINCT ON (serial) created_at
              FROM terminal_metrics
              ORDER BY serial, created_at DESC
            ) latest
            WHERE created_at < :cutoff
            """, nativeQuery = true)
    long countStale(@Param("cutoff") LocalDateTime cutoff);

    /** Total devices that have ever reported. */
    @Query(value = "SELECT COUNT(DISTINCT serial) FROM terminal_metrics", nativeQuery = true)
    long countReporting();
}

package com.tms.report.modules.transaction.repository;

import com.tms.report.modules.transaction.model.Transaction;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransactionRepository extends JpaRepository<Transaction, Long>, JpaSpecificationExecutor<Transaction> {

    Optional<Transaction> findByReference(String reference);

    Page<Transaction> findByUserId(Long userId, Pageable pageable);

    @Query(value = """
            SELECT status_code,
                   COUNT(*) as count,
                   COALESCE(SUM(amount), 0) as total
            FROM transactions
            WHERE (:startDate IS NULL OR created_at >= CAST(:startDate AS timestamp))
              AND (:endDate IS NULL OR created_at <= CAST(:endDate AS timestamp))
            GROUP BY status_code
            """, nativeQuery = true)
    List<Object[]> getSummaryByStatus(@Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);

    @Query(value = """
            SELECT t.channel as channel_name, t.status_code,
                   COALESCE(SUM(t.amount), 0) as total
            FROM transactions t
            WHERE t.status_code != 'failed'
              AND t.channel IS NOT NULL
              AND (CAST(:startDate AS timestamp) IS NULL OR t.created_at >= CAST(:startDate AS timestamp))
              AND (CAST(:endDate AS timestamp) IS NULL OR t.created_at <= CAST(:endDate AS timestamp))
            GROUP BY t.channel, t.status_code
            """, nativeQuery = true)
    List<Object[]> getChannelChartData(@Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);

    @Query(value = """
            SELECT p.name as product_name, t.status_code,
                   COALESCE(SUM(t.amount), 0) as total
            FROM transactions t
            JOIN products p ON p.id = t.product_id
            WHERE (CAST(:startDate AS timestamp) IS NULL OR t.created_at >= CAST(:startDate AS timestamp))
              AND (CAST(:endDate AS timestamp) IS NULL OR t.created_at <= CAST(:endDate AS timestamp))
            GROUP BY p.name, t.status_code
            """, nativeQuery = true)
    List<Object[]> getProductChartData(@Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);

    @Query(value = """
            SELECT t.payment_method as pm_name, t.status_code,
                   COALESCE(SUM(t.amount), 0) as total
            FROM transactions t
            WHERE t.payment_method IS NOT NULL
              AND (CAST(:startDate AS timestamp) IS NULL OR t.created_at >= CAST(:startDate AS timestamp))
              AND (CAST(:endDate AS timestamp) IS NULL OR t.created_at <= CAST(:endDate AS timestamp))
            GROUP BY t.payment_method, t.status_code
            """, nativeQuery = true)
    List<Object[]> getPaymentMethodChartData(@Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);

    @Query(value = """
            SELECT EXTRACT(HOUR FROM created_at) as h,
                   status_code,
                   COALESCE(SUM(amount), 0) as total
            FROM transactions
            WHERE (CAST(:startDate AS timestamp) IS NULL OR created_at >= CAST(:startDate AS timestamp))
              AND (CAST(:endDate AS timestamp) IS NULL OR created_at <= CAST(:endDate AS timestamp))
            GROUP BY EXTRACT(HOUR FROM created_at), status_code
            ORDER BY h
            """, nativeQuery = true)
    List<Object[]> getTimeVolumeChartData(@Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);
}

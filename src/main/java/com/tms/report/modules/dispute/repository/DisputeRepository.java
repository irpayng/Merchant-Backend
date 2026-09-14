package com.tms.report.modules.dispute.repository;

import com.tms.report.modules.dispute.model.Dispute;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DisputeRepository extends JpaRepository<Dispute, Long> {

    Page<Dispute> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Page<Dispute> findByUserIdAndStatusCodeOrderByCreatedAtDesc(Long userId, String statusCode, Pageable pageable);

    @Query("SELECT d FROM Dispute d WHERE d.userId = :userId AND "
            + "(LOWER(d.subject) LIKE LOWER(CONCAT('%', :search, '%')) OR "
            + "LOWER(d.transactionReference) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<Dispute> searchByUserId(@Param("userId") Long userId, @Param("search") String search, Pageable pageable);

    @Query("SELECT d FROM Dispute d WHERE d.userId = :userId AND d.statusCode = :status AND "
            + "(LOWER(d.subject) LIKE LOWER(CONCAT('%', :search, '%')) OR "
            + "LOWER(d.transactionReference) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<Dispute> searchByUserIdAndStatus(@Param("userId") Long userId, @Param("search") String search,
            @Param("status") String status, Pageable pageable);

    Optional<Dispute> findByIdAndUserId(Long id, Long userId);

    /**
     * Filtered dispute query with optional search, status, and date range.
     */
    @Query(value = """
            SELECT * FROM disputes d
            WHERE d.user_id = :userId
              AND (CAST(:search AS text) IS NULL
                   OR LOWER(d.subject) LIKE LOWER(CONCAT('%', CAST(:search AS text), '%'))
                   OR LOWER(d.transaction_reference) LIKE LOWER(CONCAT('%', CAST(:search AS text), '%')))
              AND (CAST(:status AS text) IS NULL OR d.status_code = CAST(:status AS text))
              AND (CAST(:dateFrom AS timestamptz) IS NULL OR d.created_at >= CAST(:dateFrom AS timestamptz))
              AND (CAST(:dateTo AS timestamptz) IS NULL OR d.created_at <= CAST(:dateTo AS timestamptz))
            ORDER BY d.created_at DESC
            """, countQuery = """
            SELECT COUNT(*) FROM disputes d
            WHERE d.user_id = :userId
              AND (CAST(:search AS text) IS NULL
                   OR LOWER(d.subject) LIKE LOWER(CONCAT('%', CAST(:search AS text), '%'))
                   OR LOWER(d.transaction_reference) LIKE LOWER(CONCAT('%', CAST(:search AS text), '%')))
              AND (CAST(:status AS text) IS NULL OR d.status_code = CAST(:status AS text))
              AND (CAST(:dateFrom AS timestamptz) IS NULL OR d.created_at >= CAST(:dateFrom AS timestamptz))
              AND (CAST(:dateTo AS timestamptz) IS NULL OR d.created_at <= CAST(:dateTo AS timestamptz))
            """, nativeQuery = true)
    Page<Dispute> findFiltered(@Param("userId") Long userId, @Param("search") String search,
            @Param("status") String status, @Param("dateFrom") LocalDateTime dateFrom,
            @Param("dateTo") LocalDateTime dateTo, Pageable pageable);
}

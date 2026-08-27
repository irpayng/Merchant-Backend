package com.tms.report.modules.audit.repository;

import com.tms.report.modules.audit.model.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /** Audit logs for a specific merchant, ordered by most recent first. */
    Page<AuditLog> findByMerchantIdOrderByCreatedAtDesc(Long merchantId, Pageable pageable);

    /** Search audit logs by user name, email, action, or path. */
    @Query("SELECT a FROM AuditLog a WHERE a.merchantId = :merchantId AND "
            + "(LOWER(a.userName) LIKE LOWER(CONCAT('%', :search, '%')) OR "
            + "LOWER(a.userEmail) LIKE LOWER(CONCAT('%', :search, '%')) OR "
            + "LOWER(a.action) LIKE LOWER(CONCAT('%', :search, '%')) OR "
            + "LOWER(a.path) LIKE LOWER(CONCAT('%', :search, '%'))) " + "ORDER BY a.createdAt DESC")
    Page<AuditLog> searchByMerchant(@Param("merchantId") Long merchantId, @Param("search") String search,
            Pageable pageable);
}

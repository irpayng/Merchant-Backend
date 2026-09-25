package com.tms.report.modules.deviceactivity.repository;

import com.tms.report.modules.deviceactivity.model.DeviceActivity;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface DeviceActivityRepository extends JpaRepository<DeviceActivity, Long> {

    /**
     * Paginated listing with optional filters. All filters are optional — pass null
     * to skip filtering on that dimension. Only returns device_login activities.
     */
    @Query(value = """
            SELECT a.* FROM activities a
            WHERE a.user_id = :merchantId
              AND a.actionable_type = 'device_login'
              AND (:search IS NULL OR (
                    LOWER(a.reference) LIKE :search
                    OR LOWER(a.description) LIKE :search
                    OR LOWER(a.action) LIKE :search
              ))
              AND (:action IS NULL OR a.action = :action)
              AND (:deviceSerial IS NULL OR a.reference = :deviceSerial)
              AND (:operatorId IS NULL OR a.actionable_id = :operatorId)
              AND (:dateFrom IS NULL OR a.created_at >= :dateFrom)
              AND (:dateTo IS NULL OR a.created_at <= :dateTo)
            ORDER BY a.created_at DESC
            """, countQuery = """
            SELECT COUNT(*) FROM activities a
            WHERE a.user_id = :merchantId
              AND a.actionable_type = 'device_login'
              AND (:search IS NULL OR (
                    LOWER(a.reference) LIKE :search
                    OR LOWER(a.description) LIKE :search
                    OR LOWER(a.action) LIKE :search
              ))
              AND (:action IS NULL OR a.action = :action)
              AND (:deviceSerial IS NULL OR a.reference = :deviceSerial)
              AND (:operatorId IS NULL OR a.actionable_id = :operatorId)
              AND (:dateFrom IS NULL OR a.created_at >= :dateFrom)
              AND (:dateTo IS NULL OR a.created_at <= :dateTo)
            """, nativeQuery = true)
    Page<DeviceActivity> findFiltered(@Param("merchantId") Long merchantId, @Param("search") String search,
            @Param("action") String action, @Param("deviceSerial") String deviceSerial,
            @Param("operatorId") Long operatorId, @Param("dateFrom") LocalDateTime dateFrom,
            @Param("dateTo") LocalDateTime dateTo, Pageable pageable);

    /** Distinct actions for filter dropdown. */
    @Query(value = "SELECT DISTINCT a.action FROM activities a WHERE a.user_id = :merchantId AND a.actionable_type = 'device_login' ORDER BY a.action", nativeQuery = true)
    List<String> findDistinctActions(@Param("merchantId") Long merchantId);

    /** Distinct device serials for filter dropdown. */
    @Query(value = "SELECT DISTINCT a.reference FROM activities a WHERE a.user_id = :merchantId AND a.actionable_type = 'device_login' AND a.reference IS NOT NULL ORDER BY a.reference", nativeQuery = true)
    List<String> findDistinctDeviceSerials(@Param("merchantId") Long merchantId);

    /** Operator IDs for filter dropdown (distinct actionable_id values). */
    @Query(value = """
            SELECT DISTINCT a.actionable_id
            FROM activities a
            WHERE a.user_id = :merchantId
              AND a.actionable_type = 'device_login'
              AND a.actionable_id IS NOT NULL
            ORDER BY a.actionable_id
            """, nativeQuery = true)
    List<Long> findDistinctOperatorIds(@Param("merchantId") Long merchantId);

    /** Count device activities by merchant. */
    @Query("SELECT COUNT(a) FROM DeviceActivity a WHERE a.userId = :merchantId AND a.actionableType = 'device_login'")
    long countByMerchantId(@Param("merchantId") Long merchantId);

    /** Count logins in the last N hours for a merchant. */
    @Query("SELECT COUNT(a) FROM DeviceActivity a WHERE a.userId = :merchantId AND a.actionableType = 'device_login' AND a.action = 'login' AND a.createdAt >= :since")
    long countRecentLogins(@Param("merchantId") Long merchantId, @Param("since") LocalDateTime since);
}

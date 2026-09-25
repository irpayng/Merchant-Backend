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
     * Actionable types that represent device activities (for filtering). Includes:
     * device_login, device_logout, pin_verification, pin_change, pin_reset,
     * account_freeze
     */
    String DEVICE_ACTIVITY_TYPES = "'device_login', 'device_logout', 'pin_verification', 'pin_change', 'pin_reset', 'account_freeze'";

    /**
     * Paginated listing with optional filters. All filters are optional — pass null
     * to skip filtering on that dimension. Returns all device-related activities.
     */
    @Query(value = """
            SELECT a.* FROM activities a
            WHERE a.user_id = :merchantId
              AND a.actionable_type IN ('device_login', 'device_logout', 'pin_verification', 'pin_change', 'pin_reset', 'account_freeze')
              AND (CAST(:search AS VARCHAR) IS NULL OR (
                    LOWER(a.reference) LIKE CAST(:search AS VARCHAR)
                    OR LOWER(a.description) LIKE CAST(:search AS VARCHAR)
                    OR LOWER(a.action) LIKE CAST(:search AS VARCHAR)
              ))
              AND (CAST(:action AS VARCHAR) IS NULL OR a.action = CAST(:action AS VARCHAR))
              AND (CAST(:deviceSerial AS VARCHAR) IS NULL OR a.reference = CAST(:deviceSerial AS VARCHAR))
              AND (CAST(:operatorId AS BIGINT) IS NULL OR a.actionable_id = CAST(:operatorId AS BIGINT))
              AND (CAST(:dateFrom AS TIMESTAMP) IS NULL OR a.created_at >= CAST(:dateFrom AS TIMESTAMP))
              AND (CAST(:dateTo AS TIMESTAMP) IS NULL OR a.created_at <= CAST(:dateTo AS TIMESTAMP))
            ORDER BY a.created_at DESC
            """, countQuery = """
            SELECT COUNT(*) FROM activities a
            WHERE a.user_id = :merchantId
              AND a.actionable_type IN ('device_login', 'device_logout', 'pin_verification', 'pin_change', 'pin_reset', 'account_freeze')
              AND (CAST(:search AS VARCHAR) IS NULL OR (
                    LOWER(a.reference) LIKE CAST(:search AS VARCHAR)
                    OR LOWER(a.description) LIKE CAST(:search AS VARCHAR)
                    OR LOWER(a.action) LIKE CAST(:search AS VARCHAR)
              ))
              AND (CAST(:action AS VARCHAR) IS NULL OR a.action = CAST(:action AS VARCHAR))
              AND (CAST(:deviceSerial AS VARCHAR) IS NULL OR a.reference = CAST(:deviceSerial AS VARCHAR))
              AND (CAST(:operatorId AS BIGINT) IS NULL OR a.actionable_id = CAST(:operatorId AS BIGINT))
              AND (CAST(:dateFrom AS TIMESTAMP) IS NULL OR a.created_at >= CAST(:dateFrom AS TIMESTAMP))
              AND (CAST(:dateTo AS TIMESTAMP) IS NULL OR a.created_at <= CAST(:dateTo AS TIMESTAMP))
            """, nativeQuery = true)
    Page<DeviceActivity> findFiltered(@Param("merchantId") Long merchantId, @Param("search") String search,
            @Param("action") String action, @Param("deviceSerial") String deviceSerial,
            @Param("operatorId") Long operatorId, @Param("dateFrom") LocalDateTime dateFrom,
            @Param("dateTo") LocalDateTime dateTo, Pageable pageable);

    /** Distinct actions for filter dropdown. */
    @Query(value = """
            SELECT DISTINCT a.action FROM activities a
            WHERE a.user_id = :merchantId
              AND a.actionable_type IN ('device_login', 'device_logout', 'pin_verification', 'pin_change', 'pin_reset', 'account_freeze')
            ORDER BY a.action
            """, nativeQuery = true)
    List<String> findDistinctActions(@Param("merchantId") Long merchantId);

    /** Distinct device serials for filter dropdown. */
    @Query(value = """
            SELECT DISTINCT a.reference FROM activities a
            WHERE a.user_id = :merchantId
              AND a.actionable_type IN ('device_login', 'device_logout', 'pin_verification', 'pin_change', 'pin_reset', 'account_freeze')
              AND a.reference IS NOT NULL
            ORDER BY a.reference
            """, nativeQuery = true)
    List<String> findDistinctDeviceSerials(@Param("merchantId") Long merchantId);

    /** Operator IDs for filter dropdown (distinct actionable_id values). */
    @Query(value = """
            SELECT DISTINCT a.actionable_id
            FROM activities a
            WHERE a.user_id = :merchantId
              AND a.actionable_type IN ('device_login', 'device_logout', 'pin_verification', 'pin_change', 'pin_reset', 'account_freeze')
              AND a.actionable_id IS NOT NULL
              AND a.actionable_id > 0
            ORDER BY a.actionable_id
            """, nativeQuery = true)
    List<Long> findDistinctOperatorIds(@Param("merchantId") Long merchantId);

    /** Count device activities by merchant. */
    @Query(value = """
            SELECT COUNT(*) FROM activities a
            WHERE a.user_id = :merchantId
              AND a.actionable_type IN ('device_login', 'device_logout', 'pin_verification', 'pin_change', 'pin_reset', 'account_freeze')
            """, nativeQuery = true)
    long countByMerchantId(@Param("merchantId") Long merchantId);

    /** Count logins in the last N hours for a merchant. */
    @Query(value = """
            SELECT COUNT(*) FROM activities a
            WHERE a.user_id = :merchantId
              AND a.actionable_type = 'device_login'
              AND a.action = 'login'
              AND a.created_at >= :since
            """, nativeQuery = true)
    long countRecentLogins(@Param("merchantId") Long merchantId, @Param("since") LocalDateTime since);
}

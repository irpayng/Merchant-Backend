package com.tms.report.modules.session.repository;

import com.tms.report.modules.session.model.Session;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SessionRepository extends JpaRepository<Session, Long> {

    /**
     * Find a session by token hash. Used during JWT validation to check if the
     * session has been revoked.
     */
    Optional<Session> findByTokenHash(String tokenHash);

    /**
     * Check if a session exists and is active (not revoked, not expired).
     */
    @Query("SELECT CASE WHEN COUNT(s) > 0 THEN true ELSE false END FROM Session s "
            + "WHERE s.tokenHash = :tokenHash AND s.revokedAt IS NULL AND s.expiresAt > :now")
    boolean isSessionActive(@Param("tokenHash") String tokenHash, @Param("now") LocalDateTime now);

    /**
     * Find all active sessions for a tms-user ID. Used for revocation.
     */
    @Query("SELECT s FROM Session s WHERE s.tmsUserId = :tmsUserId AND s.userType = :userType "
            + "AND s.revokedAt IS NULL")
    List<Session> findActiveSessionsByTmsUserId(@Param("tmsUserId") Long tmsUserId, @Param("userType") String userType);

    /**
     * Find all active sessions for a merchant user.
     */
    @Query("SELECT s FROM Session s WHERE s.merchantUserId = :merchantUserId AND s.revokedAt IS NULL")
    List<Session> findActiveSessionsByMerchantUserId(@Param("merchantUserId") Long merchantUserId);

    /**
     * Revoke all sessions for a tms-user ID. Called when tms-user triggers a
     * revocation event (block, password change, etc.).
     *
     * @return number of sessions revoked
     */
    @Modifying
    @Query("UPDATE Session s SET s.revokedAt = :now, s.revocationReason = :reason "
            + "WHERE s.tmsUserId = :tmsUserId AND s.userType = :userType AND s.revokedAt IS NULL")
    int revokeByTmsUserId(@Param("tmsUserId") Long tmsUserId, @Param("userType") String userType,
            @Param("reason") String reason, @Param("now") LocalDateTime now);

    /**
     * Revoke all sessions for a merchant user. Called when a merchant user is
     * revoked/blocked in Merchant-Backend.
     */
    @Modifying
    @Query("UPDATE Session s SET s.revokedAt = :now, s.revocationReason = :reason "
            + "WHERE s.merchantUserId = :merchantUserId AND s.revokedAt IS NULL")
    int revokeByMerchantUserId(@Param("merchantUserId") Long merchantUserId, @Param("reason") String reason,
            @Param("now") LocalDateTime now);

    /**
     * Revoke a specific session by token hash.
     */
    @Modifying
    @Query("UPDATE Session s SET s.revokedAt = :now, s.revocationReason = :reason "
            + "WHERE s.tokenHash = :tokenHash AND s.revokedAt IS NULL")
    int revokeByTokenHash(@Param("tokenHash") String tokenHash, @Param("reason") String reason,
            @Param("now") LocalDateTime now);

    /**
     * Delete expired sessions. Called by cleanup job.
     */
    @Modifying
    @Query("DELETE FROM Session s WHERE s.expiresAt < :cutoff")
    int deleteExpiredSessions(@Param("cutoff") LocalDateTime cutoff);

    /**
     * Count active sessions for a user (for monitoring/limits).
     */
    @Query("SELECT COUNT(s) FROM Session s WHERE s.merchantUserId = :merchantUserId "
            + "AND s.revokedAt IS NULL AND s.expiresAt > :now")
    long countActiveSessions(@Param("merchantUserId") Long merchantUserId, @Param("now") LocalDateTime now);
}

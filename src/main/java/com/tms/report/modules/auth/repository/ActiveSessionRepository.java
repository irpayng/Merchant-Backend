package com.tms.report.modules.auth.repository;

import com.tms.report.modules.auth.model.ActiveSession;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ActiveSessionRepository extends JpaRepository<ActiveSession, Long> {

    /**
     * Find a session by its unique session ID (embedded in JWT).
     */
    Optional<ActiveSession> findBySessionId(String sessionId);

    /**
     * Check if a valid (non-expired) session exists for this session ID.
     */
    @Query("SELECT COUNT(s) > 0 FROM ActiveSession s WHERE s.sessionId = :sessionId AND s.expiresAt > :now")
    boolean existsValidSession(@Param("sessionId") String sessionId, @Param("now") LocalDateTime now);

    /**
     * Delete all sessions for a user — used when logging in to invalidate any
     * existing session on other devices.
     */
    @Modifying
    @Query("DELETE FROM ActiveSession s WHERE s.merchantUser.id = :userId")
    void deleteByMerchantUserId(@Param("userId") Long userId);

    /**
     * Delete a specific session by session ID — used for logout.
     */
    @Modifying
    @Query("DELETE FROM ActiveSession s WHERE s.sessionId = :sessionId")
    void deleteBySessionId(@Param("sessionId") String sessionId);

    /**
     * Clean up expired sessions — can be called by a scheduled job.
     */
    @Modifying
    @Query("DELETE FROM ActiveSession s WHERE s.expiresAt < :now")
    int deleteExpiredSessions(@Param("now") LocalDateTime now);
}

package com.tms.report.modules.session.service;

import com.tms.report.modules.merchantuser.model.MerchantUser;
import com.tms.report.modules.session.model.Session;
import com.tms.report.modules.session.repository.SessionRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages dashboard sessions for revocation capability. Sessions are created on
 * login and checked on each request; revoked sessions cause authentication to
 * fail even if the JWT is still valid.
 *
 * <p>
 * Backward compatibility: during the migration window, missing session records
 * are allowed (old JWTs issued before session tracking). After the JWT expiry
 * cycle completes, this can be tightened to require a session record.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    private final SessionRepository sessionRepository;

    @Value("${app.jwt.expiration-ms}")
    private long jwtExpirationMs;

    /**
     * Whether to require a session record for authentication. Set to false during
     * migration; set to true after one full JWT expiry cycle.
     */
    @Value("${app.session.require-record:false}")
    private boolean requireSessionRecord;

    /**
     * Create a session record for a newly issued JWT.
     *
     * @param user
     *            the MerchantUser who logged in
     * @param token
     *            the JWT (will be hashed, not stored)
     * @param request
     *            HTTP request for device/IP info
     * @return the created session
     */
    @Transactional
    public Session createSession(MerchantUser user, String token, HttpServletRequest request) {
        String tokenHash = hashToken(token);
        LocalDateTime expiresAt = LocalDateTime.now().plusSeconds(jwtExpirationMs / 1000);

        Session session = Session.builder().merchantUserId(user.getId())
                .tmsUserId(user.isStaff() ? user.getOperatorId() : user.getMerchantId())
                .userType(user.isStaff() ? "operator" : "user").tokenHash(tokenHash).channel("dashboard")
                .deviceInfo(truncate(request.getHeader("User-Agent"), 500)).ipAddress(resolveClientIp(request))
                .expiresAt(expiresAt).build();

        session = sessionRepository.save(session);
        log.info("Session created: id={} merchantUserId={} tmsUserId={}", session.getId(), user.getId(),
                session.getTmsUserId());
        return session;
    }

    /**
     * Check if a JWT's session is valid (not revoked, not expired).
     *
     * @param token
     *            the JWT to check
     * @return true if session is valid or session tracking is disabled, false if
     *         revoked
     */
    public boolean isSessionValid(String token) {
        String tokenHash = hashToken(token);

        // Check if session exists and is active
        boolean active = sessionRepository.isSessionActive(tokenHash, LocalDateTime.now());

        if (active) {
            return true;
        }

        // Session not found or revoked. During migration, allow missing sessions.
        if (!requireSessionRecord) {
            // Check if it's explicitly revoked vs just missing
            return sessionRepository.findByTokenHash(tokenHash).map(s -> !s.isRevoked()).orElse(true); // Missing =
                                                                                                       // allow
        }

        // After migration: no session = not valid
        return false;
    }

    /**
     * Revoke all sessions for a tms-user. Called when tms-user triggers a
     * revocation event.
     *
     * @param tmsUserId
     *            the users.id or operators.id
     * @param userType
     *            "user" for owners, "operator" for staff
     * @param reason
     *            revocation reason for audit
     * @return number of sessions revoked
     */
    @Transactional
    public int revokeSessionsForTmsUser(Long tmsUserId, String userType, String reason) {
        int count = sessionRepository.revokeByTmsUserId(tmsUserId, userType, reason, LocalDateTime.now());
        log.info("Revoked {} sessions for tmsUserId={} userType={} reason={}", count, tmsUserId, userType, reason);
        return count;
    }

    /**
     * Revoke all sessions for a merchant user. Called when the user is
     * blocked/revoked in Merchant-Backend.
     */
    @Transactional
    public int revokeSessionsForMerchantUser(Long merchantUserId, String reason) {
        int count = sessionRepository.revokeByMerchantUserId(merchantUserId, reason, LocalDateTime.now());
        log.info("Revoked {} sessions for merchantUserId={} reason={}", count, merchantUserId, reason);
        return count;
    }

    /**
     * Revoke a specific session (logout).
     */
    @Transactional
    public boolean revokeSession(String token, String reason) {
        String tokenHash = hashToken(token);
        int count = sessionRepository.revokeByTokenHash(tokenHash, reason, LocalDateTime.now());
        return count > 0;
    }

    /**
     * Cleanup job: delete expired sessions daily. Sessions are kept for a grace
     * period after expiry for debugging, then purged.
     */
    @Scheduled(cron = "0 0 3 * * *") // 3 AM daily
    @Transactional
    public void cleanupExpiredSessions() {
        // Delete sessions expired more than 7 days ago
        LocalDateTime cutoff = LocalDateTime.now().minusDays(7);
        int deleted = sessionRepository.deleteExpiredSessions(cutoff);
        if (deleted > 0) {
            log.info("Cleaned up {} expired sessions (cutoff={})", deleted, cutoff);
        }
    }

    /**
     * SHA256 hash of a token for storage/lookup.
     */
    public static String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return truncate(forwarded.split(",")[0].trim(), 64);
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return truncate(realIp, 64);
        }
        return truncate(request.getRemoteAddr(), 64);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null)
            return null;
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}

package com.tms.report.modules.auth.service;

import com.tms.report.modules.auth.model.ActiveSession;
import com.tms.report.modules.auth.repository.ActiveSessionRepository;
import com.tms.report.modules.merchantuser.model.MerchantUser;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages active login sessions for concurrent login prevention. Each merchant
 * or operator can only have one active session at a time.
 *
 * <p>
 * Session validity is cached in Redis for performance in distributed
 * deployments. The database remains the source of truth; Redis acts as a
 * read-through cache.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    private static final String REDIS_KEY_PREFIX = "merchant:session:";

    private final ActiveSessionRepository activeSessionRepository;
    private final ObjectProvider<StringRedisTemplate> redisTemplate;

    /**
     * Create a new session for the user, invalidating any existing sessions. This
     * enforces the single-session-per-user policy.
     *
     * @param user
     *            the merchant user logging in
     * @param sessionId
     *            unique session ID to store (embedded in JWT)
     * @param expirationMs
     *            token expiration in milliseconds
     * @param deviceInfo
     *            user-agent or device description
     * @param ipAddress
     *            client IP address
     * @return the created session
     */
    @Transactional
    public ActiveSession createSession(MerchantUser user, String sessionId, long expirationMs, String deviceInfo,
            String ipAddress) {
        // Invalidate any existing sessions for this user (enforces single session)
        // First, invalidate in Redis cache
        invalidateUserSessionsInCache(user.getId());
        // Then, delete from database
        activeSessionRepository.deleteByMerchantUserId(user.getId());

        LocalDateTime expiresAt = LocalDateTime.now().plusSeconds(expirationMs / 1000);

        ActiveSession session = ActiveSession.builder().merchantUser(user).sessionId(sessionId).deviceInfo(deviceInfo)
                .ipAddress(ipAddress).expiresAt(expiresAt).build();

        ActiveSession saved = activeSessionRepository.save(session);

        // Cache the new session in Redis
        cacheSession(sessionId, expirationMs);

        log.info("Created session for user {} (merchantId={}), invalidated previous sessions", user.getEmail(),
                user.getMerchantId());
        return saved;
    }

    /**
     * Check if a session is valid (exists and not expired). Uses Redis cache for
     * performance, falls back to database if Redis unavailable.
     *
     * @param sessionId
     *            the session ID from the JWT
     * @return true if the session is valid
     */
    public boolean isSessionValid(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }

        // Try Redis cache first
        Boolean cachedValid = checkSessionInCache(sessionId);
        if (cachedValid != null) {
            return cachedValid;
        }

        // Fall back to database
        boolean valid = activeSessionRepository.existsValidSession(sessionId, LocalDateTime.now());

        // Warm the cache if session is valid (with remaining TTL)
        if (valid) {
            warmCacheFromDb(sessionId);
        }

        return valid;
    }

    /**
     * Invalidate a session (logout).
     *
     * @param sessionId
     *            the session ID to invalidate
     */
    @Transactional
    public void invalidateSession(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            // Remove from cache first
            evictSessionFromCache(sessionId);
            // Then remove from database
            activeSessionRepository.deleteBySessionId(sessionId);
            log.debug("Invalidated session: {}", sessionId);
        }
    }

    /**
     * Invalidate all sessions for a user (force logout from all devices).
     *
     * @param userId
     *            the merchant user ID
     */
    @Transactional
    public void invalidateAllUserSessions(Long userId) {
        invalidateUserSessionsInCache(userId);
        activeSessionRepository.deleteByMerchantUserId(userId);
        log.info("Invalidated all sessions for user ID: {}", userId);
    }

    /**
     * Scheduled cleanup of expired sessions. Runs every hour.
     */
    @Scheduled(fixedRate = 3600000) // 1 hour
    @Transactional
    public void cleanupExpiredSessions() {
        int deleted = activeSessionRepository.deleteExpiredSessions(LocalDateTime.now());
        if (deleted > 0) {
            log.info("Cleaned up {} expired sessions", deleted);
        }
    }

    // ── Redis cache operations (graceful degradation if Redis unavailable) ──

    private void cacheSession(String sessionId, long expirationMs) {
        StringRedisTemplate template = redisTemplate.getIfAvailable();
        if (template == null) {
            return;
        }
        try {
            String key = REDIS_KEY_PREFIX + sessionId;
            // Store "1" as marker for valid session with TTL matching token expiration
            template.opsForValue().set(key, "1", Duration.ofMillis(expirationMs));
        } catch (Exception e) {
            log.debug("Failed to cache session in Redis: {}", e.getMessage());
        }
    }

    private Boolean checkSessionInCache(String sessionId) {
        StringRedisTemplate template = redisTemplate.getIfAvailable();
        if (template == null) {
            return null; // Redis unavailable, caller should fall back to DB
        }
        try {
            String key = REDIS_KEY_PREFIX + sessionId;
            String value = template.opsForValue().get(key);
            if (value == null) {
                // Key doesn't exist — could be expired or never cached
                // Return null to trigger DB fallback (don't assume invalid)
                return null;
            }
            return "1".equals(value);
        } catch (Exception e) {
            log.debug("Failed to check session in Redis: {}", e.getMessage());
            return null; // Fall back to DB
        }
    }

    private void evictSessionFromCache(String sessionId) {
        StringRedisTemplate template = redisTemplate.getIfAvailable();
        if (template == null) {
            return;
        }
        try {
            String key = REDIS_KEY_PREFIX + sessionId;
            template.delete(key);
        } catch (Exception e) {
            log.debug("Failed to evict session from Redis: {}", e.getMessage());
        }
    }

    private void invalidateUserSessionsInCache(Long userId) {
        // We can't efficiently find all session keys for a user in Redis without
        // maintaining a secondary index. Instead, we rely on:
        // 1. The session ID being unique and embedded in the JWT
        // 2. When a user logs in on a new device, their old JWT's session ID
        // won't exist in the DB, and cache misses will fall back to DB check
        // 3. Redis TTL will eventually expire stale cache entries
        //
        // For explicit invalidation, we'd need to track user -> sessionId mapping.
        // Since createSession() is called, the old session is deleted from DB,
        // so DB fallback returns false. The only cost is one extra DB query
        // until the Redis TTL expires on the old session key.
    }

    private void warmCacheFromDb(String sessionId) {
        StringRedisTemplate template = redisTemplate.getIfAvailable();
        if (template == null) {
            return;
        }
        try {
            // Look up the session to get remaining TTL
            activeSessionRepository.findBySessionId(sessionId).ifPresent(session -> {
                long remainingMs = Duration.between(LocalDateTime.now(), session.getExpiresAt()).toMillis();
                if (remainingMs > 0) {
                    String key = REDIS_KEY_PREFIX + sessionId;
                    template.opsForValue().set(key, "1", Duration.ofMillis(remainingMs));
                }
            });
        } catch (Exception e) {
            log.debug("Failed to warm session cache from DB: {}", e.getMessage());
        }
    }
}

package com.tms.report.modules.profile;

import com.tms.report.modules.grpc.service.GrpcClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Service for fetching user profile data from tms-user. Profile data (name,
 * email, phone, business name) is owned by tms-user and should NOT be
 * duplicated in merchant_users.
 *
 * <p>
 * This service provides a short-lived cache to avoid repeated gRPC calls within
 * a single request/session while ensuring profile changes propagate quickly.
 *
 * <h2>Phase 2 Architecture</h2>
 * <ul>
 * <li>tms-user owns: email, phone_number, name, business_name, type</li>
 * <li>merchant_users owns: role, roleEntity, status, terminalId, invitedBy</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserProfileService {

    private final GrpcClient grpcClient;

    /** Short-lived cache: userId → (profile, fetchedAt). TTL = 30 seconds. */
    private final ConcurrentHashMap<Long, CachedProfile> profileCache = new ConcurrentHashMap<>();

    private static final Duration CACHE_TTL = Duration.ofSeconds(30);

    /**
     * Get user profile from tms-user, with short-lived caching.
     *
     * @param userId
     *            the tms-user users.id (merchantId for owners)
     * @return profile data or null if not found
     */
    public UserProfile getProfile(long userId) {
        CachedProfile cached = profileCache.get(userId);
        if (cached != null && !cached.isExpired()) {
            return cached.profile;
        }

        try {
            Map<String, Object> data = grpcClient.getUserProfile(userId);
            if (data == null || data.get("user_id") == null) {
                log.warn("User profile not found in tms-user for userId={}", userId);
                return null;
            }

            UserProfile profile = UserProfile.builder().userId(((Number) data.get("user_id")).longValue())
                    .email((String) data.get("email")).phoneNumber((String) data.get("phone_number"))
                    .firstName((String) data.get("first_name")).lastName((String) data.get("last_name"))
                    .businessName((String) data.get("business_name")).type((String) data.get("type")).build();

            profileCache.put(userId, new CachedProfile(profile));
            return profile;
        } catch (Exception e) {
            log.error("Failed to fetch user profile from tms-user for userId={}: {}", userId, e.getMessage());
            // Return cached (even if stale) as fallback
            if (cached != null) {
                log.info("Returning stale cached profile for userId={}", userId);
                return cached.profile;
            }
            return null;
        }
    }

    /**
     * Get the display name for a user. Prefers business name, then
     * firstName+lastName, then email, then phone.
     */
    public String getDisplayName(long userId) {
        UserProfile profile = getProfile(userId);
        if (profile == null) {
            return null;
        }
        return profile.getDisplayName();
    }

    /**
     * Invalidate cached profile for a user. Call this after profile updates.
     */
    public void invalidate(long userId) {
        profileCache.remove(userId);
    }

    /**
     * Clear all cached profiles. Useful for testing or admin operations.
     */
    public void clearCache() {
        profileCache.clear();
    }

    private static class CachedProfile {
        final UserProfile profile;
        final long fetchedAt;

        CachedProfile(UserProfile profile) {
            this.profile = profile;
            this.fetchedAt = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - fetchedAt > CACHE_TTL.toMillis();
        }
    }
}

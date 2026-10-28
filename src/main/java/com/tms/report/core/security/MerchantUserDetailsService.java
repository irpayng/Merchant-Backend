package com.tms.report.core.security;

import com.tms.report.modules.merchantuser.model.MerchantUser;
import com.tms.report.modules.merchantuser.repository.MerchantUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Loads a merchant dashboard login (owner/staff) for JWT validation.
 *
 * <p>
 * The "username" is the JWT subject, which uses a prefixed ID format:
 * <ul>
 * <li>{@code u:<merchantId>} — owner, lookup by tms-user users.id</li>
 * <li>{@code o:<operatorId>} — staff, lookup by tms-user operators.id</li>
 * </ul>
 *
 * <p>
 * For backward compatibility with tokens issued before this change, legacy
 * subjects (email or phone) are also supported during a migration window. These
 * are logged for monitoring and will be removed after the token expiry window.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MerchantUserDetailsService implements UserDetailsService {

    private final MerchantUserRepository merchantUserRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        if (username == null || username.isBlank()) {
            throw new UsernameNotFoundException("Account not found: empty subject");
        }

        MerchantUser user = resolveUser(username);
        if (user == null) {
            throw new UsernameNotFoundException("Account not found: " + username);
        }
        return new MerchantUserDetails(user);
    }

    /**
     * Resolve a MerchantUser from the JWT subject. Supports both the new prefixed
     * ID format and legacy email/phone subjects.
     */
    private MerchantUser resolveUser(String subject) {
        // New format: "u:<merchantId>" for owners
        if (subject.startsWith("u:")) {
            try {
                Long merchantId = Long.parseLong(subject.substring(2));
                return merchantUserRepository.findOwnerByMerchantId(merchantId).orElse(null);
            } catch (NumberFormatException e) {
                log.warn("Invalid owner subject format: {}", subject);
                return null;
            }
        }

        // New format: "o:<operatorId>" for staff
        if (subject.startsWith("o:")) {
            try {
                Long operatorId = Long.parseLong(subject.substring(2));
                return merchantUserRepository.findByOperatorId(operatorId).orElse(null);
            } catch (NumberFormatException e) {
                log.warn("Invalid operator subject format: {}", subject);
                return null;
            }
        }

        // Legacy format: email or phone (migration window — remove after token expiry)
        log.info("Legacy JWT subject detected: {}. User should re-login to get new token format.",
                maskIdentifier(subject));
        return resolveLegacyUser(subject);
    }

    /**
     * Resolve user from legacy email/phone subject. This supports tokens issued
     * before the switch to ID-based subjects.
     */
    private MerchantUser resolveLegacyUser(String identifier) {
        String normalized = identifier.trim();

        // Try email first (most common), then phone
        if (looksLikeEmail(normalized)) {
            return merchantUserRepository.findByEmail(normalized.toLowerCase())
                    .or(() -> merchantUserRepository.findByPhoneNumber(normalized)).orElse(null);
        } else {
            // Phone-like or ambiguous — try phone first
            return merchantUserRepository.findByPhoneNumber(normalized)
                    .or(() -> merchantUserRepository.findByEmail(normalized.toLowerCase())).orElse(null);
        }
    }

    private static boolean looksLikeEmail(String identifier) {
        return identifier.contains("@");
    }

    /**
     * Mask identifier for logging (privacy).
     */
    private static String maskIdentifier(String id) {
        if (id == null || id.length() < 4) {
            return "***";
        }
        return id.substring(0, 2) + "***" + id.substring(id.length() - 2);
    }
}

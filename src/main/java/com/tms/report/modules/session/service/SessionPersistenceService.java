package com.tms.report.modules.session.service;

import com.tms.report.modules.merchantuser.model.MerchantUser;
import com.tms.report.modules.session.model.Session;
import com.tms.report.modules.session.repository.SessionRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles session persistence in a separate transaction. This is extracted from
 * SessionService to ensure REQUIRES_NEW propagation works correctly (Spring AOP
 * proxies don't intercept internal method calls).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionPersistenceService {

    private final SessionRepository sessionRepository;

    @Value("${app.jwt.expiration-ms}")
    private long jwtExpirationMs;

    /**
     * Persist a session record in a new transaction. If this fails, it won't affect
     * the caller's transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Session persistSession(MerchantUser user, String tokenHash, HttpServletRequest request) {
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

package com.tms.report.core.security;

import com.tms.report.modules.admin.model.Admin;
import com.tms.report.modules.privilege.model.Privilege;
import com.tms.report.modules.role.model.Role;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Validates JWTs and populates the Spring Security context.
 *
 * <p>
 * User details are cached in Redis (key
 * {@code tms-report:admin-details:<username>}) with a short TTL so every
 * replica shares the same principal data. This replaces the previous per-pod
 * {@code ConcurrentHashMap} cache. Reads fail-open: if Redis is down we fall
 * back to the database.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final AdminDetailsService adminDetailsService;
    private final ObjectProvider<StringRedisTemplate> redisTemplate;
    private final ObjectMapper objectMapper;

    private static final Duration CACHE_TTL = Duration.ofMinutes(1);
    private static final String KEY_PREFIX = "super-merchant:admin-details:";

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");

        // Support token from query param for SSE endpoints (EventSource can't send
        // headers)
        String jwt = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            jwt = authHeader.substring(7);
        } else if (request.getRequestURI().endsWith("/stream")) {
            String tokenParam = request.getParameter("token");
            if (tokenParam != null && !tokenParam.isEmpty()) {
                jwt = tokenParam;
            }
        }

        if (jwt == null) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            String username = jwtService.extractUsername(jwt);

            if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                UserDetails userDetails = getCachedUser(username);

                if (jwtService.isTokenValid(jwt, userDetails)) {
                    var authToken = new UsernamePasswordAuthenticationToken(userDetails, null,
                            userDetails.getAuthorities());
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                }
            }
        } catch (Exception ignored) {
            // Invalid token — let the request proceed unauthenticated
        }

        filterChain.doFilter(request, response);
    }

    private UserDetails getCachedUser(String username) {
        String key = KEY_PREFIX + username;

        AdminSnapshot snapshot = readSnapshot(key);
        if (snapshot != null) {
            return new AdminDetails(snapshot.toAdmin());
        }

        UserDetails fresh = adminDetailsService.loadUserByUsername(username);
        if (fresh instanceof AdminDetails details) {
            writeSnapshot(key, AdminSnapshot.from(details.getAdmin()));
        }
        return fresh;
    }

    private AdminSnapshot readSnapshot(String key) {
        StringRedisTemplate template = redisTemplate.getIfAvailable();
        if (template == null) {
            return null;
        }
        try {
            String json = template.opsForValue().get(key);
            if (json == null) {
                return null;
            }
            return objectMapper.readValue(json, AdminSnapshot.class);
        } catch (Exception e) {
            log.debug("Redis read failed for {} (treating as cache miss): {}", key, e.getMessage());
            return null;
        }
    }

    private void writeSnapshot(String key, AdminSnapshot snapshot) {
        StringRedisTemplate template = redisTemplate.getIfAvailable();
        if (template == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(snapshot);
            template.opsForValue().set(key, json, CACHE_TTL);
        } catch (Exception e) {
            log.debug("Redis write failed for {} (next call will re-load): {}", key, e.getMessage());
        }
    }

    /**
     * Serializable snapshot of {@link Admin} for Redis caching. We can't serialize
     * {@link Admin} directly because {@code @JsonIgnore} strips the password, which
     * downstream controllers need for step-up auth. Role and privilege graphs are
     * flattened to just the codes used by {@link AdminDetails#getAuthorities()}.
     */
    public record AdminSnapshot(Long id, String name, String email, String phoneNumber, String password,
            String bankCode, LocalDateTime blockedAt, String blockedReason, LocalDateTime emailVerifiedAt,
            List<String> roleCodes, List<String> privilegeCodes, LocalDateTime createdAt, LocalDateTime updatedAt) {

        public static AdminSnapshot from(Admin admin) {
            List<String> roleCodes = admin.getRoles().stream().map(Role::getCode).toList();
            List<String> privilegeCodes = admin.getRoles().stream().flatMap(r -> r.getPrivileges().stream())
                    .map(Privilege::getCode).distinct().toList();
            return new AdminSnapshot(admin.getId(), admin.getName(), admin.getEmail(), admin.getPhoneNumber(),
                    admin.getPassword(), admin.getBankCode(), admin.getBlockedAt(), admin.getBlockedReason(),
                    admin.getEmailVerifiedAt(), roleCodes, privilegeCodes, admin.getCreatedAt(), admin.getUpdatedAt());
        }

        /**
         * Rehydrate a transient {@link Admin} suitable for {@link AdminDetails}. The
         * entity is detached and has synthetic {@link Role}/{@link Privilege} instances
         * carrying only the codes that participate in authorization checks
         * ({@code isSuperAdmin()}, {@code hasPrivilege()}, and
         * {@code getAuthorities()}).
         */
        public Admin toAdmin() {
            Admin admin = new Admin();
            admin.setId(id);
            admin.setName(name);
            admin.setEmail(email);
            admin.setPhoneNumber(phoneNumber);
            admin.setPassword(password);
            admin.setBankCode(bankCode);
            admin.setBlockedAt(blockedAt);
            admin.setBlockedReason(blockedReason);
            admin.setEmailVerifiedAt(emailVerifiedAt);
            admin.setCreatedAt(createdAt);
            admin.setUpdatedAt(updatedAt);

            Set<Role> rolesSet = new HashSet<>();
            Set<Privilege> privilegeSet = privilegeCodes.stream().map(code -> {
                Privilege p = new Privilege();
                p.setCode(code);
                return p;
            }).collect(Collectors.toSet());

            for (String roleCode : roleCodes) {
                Role role = new Role();
                role.setCode(roleCode);
                role.setPrivileges(privilegeSet);
                rolesSet.add(role);
            }
            admin.setRoles(rolesSet);
            return admin;
        }
    }
}

package com.tms.report.modules.activity.aspect;

import com.tms.report.core.security.AdminDetails;
import com.tms.report.modules.activity.annotation.LogActivity;
import com.tms.report.modules.activity.model.Activity;
import com.tms.report.modules.activity.repository.ActivityRepository;
import com.tms.report.modules.admin.repository.AdminRepository;
import com.tms.report.modules.user.repository.ProfileRepository;
import com.tms.report.modules.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * AOP aspect that records admin activities after annotated controller methods
 * succeed.
 *
 * Description placeholders: {admin} → authenticated admin name (subject) {id}
 * → @PathVariable value {user} → resolved affected user's full name (object),
 * via userFrom strategy {body.field} → field from @RequestBody (Map key or bean
 * getter)
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class ActivityLoggingAspect {

    private final ActivityRepository activityRepository;
    private final UserRepository userRepository;
    private final ProfileRepository profileRepository;
    private final AdminRepository adminRepository;
    private final EntityManager entityManager;

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^}]+)}");

    @AfterReturning("@annotation(logActivity)")
    public void recordActivity(JoinPoint joinPoint, LogActivity logActivity) {
        try {
            Long adminId = null;
            String adminName = "System";

            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof AdminDetails details) {
                adminId = details.getAdmin().getId();
                adminName = details.getAdmin().getName() != null ? details.getAdmin().getName() : "Admin";
            }

            MethodSignature sig = (MethodSignature) joinPoint.getSignature();
            Method method = sig.getMethod();
            Object[] args = joinPoint.getArgs();
            Annotation[][] paramAnnotations = method.getParameterAnnotations();

            Object pathId = null;
            Object requestBody = null;

            for (int i = 0; i < args.length; i++) {
                for (Annotation ann : paramAnnotations[i]) {
                    if (ann instanceof PathVariable)
                        pathId = args[i];
                    if (ann instanceof RequestBody)
                        requestBody = args[i];
                }
            }

            String userName = resolveUserName(logActivity.userFrom(), pathId, requestBody);

            String description = resolvePlaceholders(logActivity.description(), adminName, pathId, requestBody,
                    userName);

            activityRepository.save(Activity.builder().adminId(adminId).action(logActivity.action())
                    .description(description).createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        } catch (Exception e) {
            log.error("Failed to record activity: {}", e.getMessage());
        }
    }

    private String resolvePlaceholders(String template, String adminName, Object pathId, Object body, String userName) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String replacement = switch (key) {
                case "admin" -> adminName;
                case "id" -> pathId != null ? pathId.toString() : "";
                case "user" -> userName != null ? userName : "unknown user";
                default -> resolveBodyField(key, body);
            };
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Resolves the affected user's full name based on the userFrom strategy.
     *
     * Strategies: "body.email:fieldName" – read email from body field, find user,
     * then profile "entity:ClassName" – use pathId to load entity, read its userId,
     * then profile "admin:id" – use pathId to look up another admin's name
     */
    private String resolveUserName(String userFrom, Object pathId, Object body) {
        if (userFrom == null || userFrom.isBlank())
            return null;
        try {
            if (userFrom.startsWith("body.email:")) {
                String field = userFrom.substring("body.email:".length());
                String email = readBodyField(field, body);
                if (email == null || email.isBlank())
                    return null;
                return userRepository.findByEmail(email.toLowerCase().trim())
                        .map(u -> profileFullName(u.getId(), u.getEmail())).orElse(email);
            }
            if (userFrom.equals("admin:id")) {
                if (pathId == null)
                    return null;
                Long id = Long.parseLong(pathId.toString());
                return adminRepository.findById(id).map(a -> a.getName() != null ? a.getName() : a.getEmail())
                        .orElse(null);
            }
            if (userFrom.startsWith("entity:")) {
                String entityName = userFrom.substring("entity:".length());
                if (pathId == null)
                    return null;
                Long id = Long.parseLong(pathId.toString());
                Long userId = lookupUserIdFromEntity(entityName, id);
                if (userId == null)
                    return null;
                return userRepository.findById(userId).map(u -> profileFullName(u.getId(), u.getEmail())).orElse(null);
            }
        } catch (Exception e) {
            log.debug("Could not resolve user name for strategy '{}': {}", userFrom, e.getMessage());
        }
        return null;
    }

    private String profileFullName(Long userId, String fallback) {
        return profileRepository.findByUserId(userId).map(p -> {
            String full = p.getFullName();
            return full != null ? full : fallback;
        }).orElse(fallback);
    }

    @SuppressWarnings("unchecked")
    private Long lookupUserIdFromEntity(String entityName, Long id) {
        String table = switch (entityName) {
            case "Bvn" -> "bvns";
            case "Nin" -> "nins";
            case "Document" -> "documents";
            case "Address" -> "address_verifications";
            case "Dispute" -> "disputes";
            case "Terminal" -> "terminals";
            case "BusinessApplication" -> "business_applications";
            case "User" -> "users";
            default -> null;
        };
        if (table == null)
            return null;

        // Documents use documentable_id, disputes use transaction→user, users are
        // looked up by their own id, rest use user_id.
        String userIdColumn = switch (entityName) {
            case "Document" -> "documentable_id";
            case "Dispute" -> null; // special handling
            case "User" -> "id";
            default -> "user_id";
        };

        if ("Dispute".equals(entityName)) {
            try {
                Object result = entityManager.createNativeQuery(
                        "SELECT t.user_id FROM disputes d JOIN transactions t ON t.id = d.transaction_id WHERE d.id = :id")
                        .setParameter("id", id).getSingleResult();
                return result != null ? ((Number) result).longValue() : null;
            } catch (Exception e) {
                return null;
            }
        }

        try {
            Object result = entityManager
                    .createNativeQuery("SELECT " + userIdColumn + " FROM " + table + " WHERE id = :id")
                    .setParameter("id", id).getSingleResult();
            return result != null ? ((Number) result).longValue() : null;
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private String resolveBodyField(String key, Object body) {
        if (body == null || !key.startsWith("body."))
            return "{" + key + "}";
        return readBodyField(key.substring(5), body);
    }

    @SuppressWarnings("unchecked")
    private String readBodyField(String field, Object body) {
        if (body == null)
            return "";
        if (body instanceof Map<?, ?> map) {
            Object val = map.get(field);
            return val != null ? val.toString() : "";
        }
        try {
            String getter = "get" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
            Object val = body.getClass().getMethod(getter).invoke(body);
            return val != null ? val.toString() : "";
        } catch (Exception e) {
            return "";
        }
    }
}

package com.tms.report.modules.onboarding.service;

import com.tms.report.core.filter.QueryFilterHelper;
import com.tms.report.core.security.TenantScope;
import com.tms.report.core.storage.S3UrlGenerator;
import com.tms.report.core.util.Dates;
import com.tms.report.modules.onboarding.dto.OnboardingDto;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.Query;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the {@code onboardings} table replicated from the onboarding-service.
 * Each row is an agent/merchant registration that is in progress or complete.
 * The admin dashboard uses this to monitor sign-ups as they move through BVN,
 * phone, email, and liveliness validation, and to inspect the captured details
 * (including the BVN lookup result and selfie) of a single onboarding.
 *
 * <p>
 * The {@code bvn} column is sensitive PII: it is masked in the list view and
 * only returned in full by {@link #showDetail(Long)} for KYC review — mirroring
 * the existing BVN-verification module.
 */
@Service
@RequiredArgsConstructor
public class OnboardingService {

    private final EntityManager entityManager;
    private final TenantScope tenantScope;

    @Value("${AWS_BUCKET:ircms-public-images}")
    private String s3Bucket;

    @Value("${AWS_DEFAULT_REGION:eu-north-1}")
    private String s3Region;

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Page<OnboardingDto> index(Map<String, String> params) {
        int page = Integer.parseInt(params.getOrDefault("page", "1")) - 1;
        int limit = Integer.parseInt(params.getOrDefault("limit", "15"));

        StringBuilder where = new StringBuilder("WHERE 1=1");
        Map<String, Object> qp = new HashMap<>();

        String search = params.get("search");
        if (search != null && !search.isBlank()) {
            String[] words = search.toLowerCase().trim().split("\\s+");
            String fullName = "LOWER(COALESCE(o.first_name, '') || ' ' || COALESCE(o.middle_name, '') || ' ' || COALESCE(o.last_name, ''))";
            where.append(" AND (LOWER(o.email) LIKE :search OR o.phone_number LIKE :search "
                    + "OR o.bvn_phone_number LIKE :search OR o.bvn LIKE :search OR o.reference LIKE :search OR (");
            for (int i = 0; i < words.length; i++) {
                if (i > 0)
                    where.append(" AND ");
                String param = "nameWord" + i;
                where.append(fullName).append(" LIKE :").append(param);
                qp.put(param, "%" + words[i] + "%");
            }
            where.append("))");
            qp.put("search", "%" + search.toLowerCase() + "%");
        }

        // Coarse status filter. "Completed" is authoritative: a user account was
        // created from this onboarding (users.onboarding_id back-reference). The
        // validation flags only distinguish "started" from "in_progress" for
        // onboardings that have not yet produced a user — they are NOT a reliable
        // completion signal (email validation is optional, and BVN-phone signups
        // never set phone_number_is_validated).
        String userExists = "EXISTS (SELECT 1 FROM users u WHERE u.onboarding_id = o.id)";
        String status = params.get("status");
        if (status != null && !status.isBlank()) {
            switch (status) {
                case "completed" -> where.append(" AND ").append(userExists);
                case "in_progress" ->
                    where.append(" AND NOT ").append(userExists).append(" AND o.bvn_is_validated = true");
                case "started" ->
                    where.append(" AND NOT ").append(userExists).append(" AND o.bvn_is_validated = false");
                default -> {
                    // Unknown status value — no additional filter.
                }
            }
        }

        QueryFilterHelper.applyDates(where, qp, params, "o.created_at");
        // Per-bank tenant scope: only onboardings that produced this bank's merchants.
        tenantScope.appendOnboardingScope(where, qp, "o.id");

        String sql = """
                SELECT o.id, o.reference,
                       TRIM(COALESCE(o.first_name, '') || ' ' || COALESCE(o.last_name, '')) AS name,
                       o.email, o.phone_number, o.bvn_phone_number, o.is_bvn_phone_number,
                       o.bvn_is_validated, o.phone_number_is_validated, o.email_is_validated,
                       o.liveliness_is_validated, o.created_at,
                       EXISTS (SELECT 1 FROM users u WHERE u.onboarding_id = o.id) AS has_user
                FROM onboardings o
                """ + where + " ORDER BY o.created_at DESC";

        String countSql = "SELECT COUNT(*) FROM onboardings o " + where;

        Query countQuery = entityManager.createNativeQuery(countSql);
        Query dataQuery = entityManager.createNativeQuery(sql);

        qp.forEach(countQuery::setParameter);
        qp.forEach(dataQuery::setParameter);

        long total = ((Number) countQuery.getSingleResult()).longValue();
        dataQuery.setFirstResult(page * limit);
        dataQuery.setMaxResults(limit);

        List<Object[]> rows = dataQuery.getResultList();
        List<OnboardingDto> dtos = rows.stream().map(this::toListDto).toList();

        return new PageImpl<>(dtos, PageRequest.of(page, limit), total);
    }

    public Map<String, Object> getSummary(Map<String, String> params) {
        StringBuilder where = new StringBuilder("WHERE 1=1");
        Map<String, Object> qp = new HashMap<>();
        QueryFilterHelper.applyDates(where, qp, params, "o.created_at");
        tenantScope.appendOnboardingScope(where, qp, "o.id");

        String sql = """
                SELECT
                    COUNT(*) AS total,
                    SUM(CASE WHEN EXISTS (SELECT 1 FROM users u WHERE u.onboarding_id = o.id)
                             THEN 1 ELSE 0 END) AS completed,
                    SUM(CASE WHEN NOT EXISTS (SELECT 1 FROM users u WHERE u.onboarding_id = o.id)
                              AND o.bvn_is_validated = true THEN 1 ELSE 0 END) AS in_progress,
                    SUM(CASE WHEN NOT EXISTS (SELECT 1 FROM users u WHERE u.onboarding_id = o.id)
                              AND o.bvn_is_validated = false THEN 1 ELSE 0 END) AS started
                FROM onboardings o
                """ + where;

        Query q = entityManager.createNativeQuery(sql);
        qp.forEach(q::setParameter);
        Object[] r = (Object[]) q.getSingleResult();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", asInt(r[0]));
        stats.put("completed", asInt(r[1]));
        stats.put("in_progress", asInt(r[2]));
        stats.put("started", asInt(r[3]));
        return stats;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> showDetail(Long id) {
        String sql = """
                SELECT o.id, o.reference, o.email, o.phone_number, o.is_bvn_phone_number,
                       o.bvn_is_validated, o.phone_number_is_validated, o.email_is_validated,
                       o.liveliness_is_validated, o.bvn, o.bvn_phone_number,
                       o.first_name, o.middle_name, o.last_name, o.gender, o.date_of_birth,
                       o.country_id, o.state_id, o.lga, o.address, o.selfie_url,
                       o.created_at, o.updated_at,
                       c.name AS country_name, s.name AS state_name,
                       u.id AS user_id, u.type AS user_type
                FROM onboardings o
                LEFT JOIN countries c ON c.id = o.country_id
                LEFT JOIN states s ON s.id = o.state_id
                LEFT JOIN users u ON u.onboarding_id = o.id
                WHERE o.id = :id
                """;

        Map<String, Object> scopeBinds = new HashMap<>();
        StringBuilder scope = new StringBuilder();
        tenantScope.appendOnboardingScope(scope, scopeBinds, "o.id");
        sql += scope.toString();

        Object[] r;
        try {
            Query dq = entityManager.createNativeQuery(sql).setParameter("id", id);
            scopeBinds.forEach(dq::setParameter);
            r = (Object[]) dq.getSingleResult();
        } catch (NoResultException e) {
            throw new jakarta.persistence.EntityNotFoundException("Onboarding not found: " + id);
        }

        boolean bvnValidated = bool(r[5]);
        boolean phoneValidated = bool(r[6]);
        boolean emailValidated = bool(r[7]);
        boolean livenessValidated = bool(r[8]);
        Long userId = r[25] != null ? ((Number) r[25]).longValue() : null;
        boolean hasUser = userId != null;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", ((Number) r[0]).longValue());
        data.put("reference", str(r[1]));
        data.put("email", str(r[2]));
        data.put("phone_number", str(r[3]));
        data.put("is_bvn_phone_number", bool(r[4]));
        data.put("bvn_is_validated", bvnValidated);
        data.put("phone_number_is_validated", phoneValidated);
        data.put("email_is_validated", emailValidated);
        data.put("liveliness_is_validated", livenessValidated);
        data.put("bvn", str(r[9]));
        data.put("bvn_phone_number", str(r[10]));
        data.put("first_name", str(r[11]));
        data.put("middle_name", str(r[12]));
        data.put("last_name", str(r[13]));
        data.put("name", buildName(str(r[11]), str(r[12]), str(r[13])));
        data.put("gender", str(r[14]));
        data.put("date_of_birth", str(r[15]));
        data.put("lga", str(r[18]));
        data.put("address", str(r[19]));
        data.put("country", str(r[23]));
        data.put("state", str(r[24]));
        data.put("status", deriveStatus(hasUser, bvnValidated));
        // The account created from this onboarding, when completed. null while the
        // onboarding is still in progress.
        data.put("user_id", userId);
        data.put("user_type", str(r[26]));
        data.put("created_at", Dates.toLocalDateTime(r[21]));
        data.put("updated_at", Dates.toLocalDateTime(r[22]));
        data.put("image", buildImage(((Number) r[0]).longValue(), str(r[20])));

        return data;
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private OnboardingDto toListDto(Object[] row) {
        boolean bvnValidated = bool(row[7]);
        boolean phoneValidated = bool(row[8]);
        boolean emailValidated = bool(row[9]);
        boolean livenessValidated = bool(row[10]);
        boolean hasUser = bool(row[12]);
        return OnboardingDto.builder().id(((Number) row[0]).longValue()).reference(str(row[1])).name(str(row[2]))
                .email(str(row[3])).phoneNumber(str(row[4])).bvnPhoneNumber(str(row[5])).isBvnPhoneNumber(bool(row[6]))
                .bvnIsValidated(bvnValidated).phoneNumberIsValidated(phoneValidated).emailIsValidated(emailValidated)
                .livelinessIsValidated(livenessValidated).status(deriveStatus(hasUser, bvnValidated))
                .createdAt(Dates.toLocalDateTime(row[11])).build();
    }

    /**
     * Collapse onboarding progress into a coarse status. A linked user account
     * ({@code hasUser}) is the authoritative "completed" signal — the validation
     * flags alone are not, because email validation is optional during onboarding
     * and BVN-phone signups never set {@code phone_number_is_validated}. For
     * onboardings with no user yet, {@code started} means BVN not even validated,
     * otherwise {@code in_progress}.
     */
    private String deriveStatus(boolean hasUser, boolean bvnValidated) {
        if (hasUser) {
            return "completed";
        }
        return bvnValidated ? "in_progress" : "started";
    }

    /**
     * Load the onboarding's BVN photo and selfie. The selfie is stored as a bare S3
     * key on {@code onboardings.selfie_url}; the BVN photo and any liveliness
     * frames live in the polymorphic {@code images} table keyed on
     * {@code imageable_type='onboardings'}.
     */
    private Map<String, Object> buildImage(Long onboardingId, String selfieKey) {
        String bvnPhoto = imageUrl(onboardingId, "bvn_photo");
        String selfie = presign(selfieKey);
        if (selfie == null) {
            selfie = imageUrl(onboardingId, "selfie");
        }

        Map<String, Object> image = new LinkedHashMap<>();
        image.put("bvn_photo", bvnPhoto);
        image.put("selfie", selfie);
        return image;
    }

    private String imageUrl(Long onboardingId, String name) {
        try {
            Object url = entityManager
                    .createNativeQuery("SELECT url FROM images WHERE imageable_type = 'onboardings' "
                            + "AND imageable_id = :oid AND name = :name LIMIT 1")
                    .setParameter("oid", onboardingId.toString()).setParameter("name", name).getSingleResult();
            return url != null ? presign(url.toString()) : null;
        } catch (NoResultException e) {
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String presign(String path) {
        if (path == null || path.isBlank())
            return null;
        if (path.startsWith("http"))
            return path;
        try {
            return S3UrlGenerator.temporaryUrl(path);
        } catch (Exception e) {
            return String.format("https://%s.s3.%s.amazonaws.com/%s", s3Bucket, s3Region, path);
        }
    }

    private String buildName(String first, String middle, String last) {
        StringBuilder sb = new StringBuilder();
        if (first != null && !first.isBlank())
            sb.append(first.trim());
        if (middle != null && !middle.isBlank())
            sb.append(sb.length() > 0 ? " " : "").append(middle.trim());
        if (last != null && !last.isBlank())
            sb.append(sb.length() > 0 ? " " : "").append(last.trim());
        return sb.length() > 0 ? sb.toString() : null;
    }

    private String str(Object o) {
        return o != null ? o.toString().trim() : null;
    }

    private boolean bool(Object o) {
        return o instanceof Boolean b && b;
    }

    private int asInt(Object o) {
        return o != null ? ((Number) o).intValue() : 0;
    }
}

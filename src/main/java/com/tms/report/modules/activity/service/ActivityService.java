package com.tms.report.modules.activity.service;

import com.tms.report.core.security.MerchantScope;
import com.tms.report.modules.activity.dto.ActivityDto;
import com.tms.report.modules.activity.repository.ActivityRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ActivityService {

    private final ActivityRepository activityRepository;
    private final EntityManager entityManager;
    private final MerchantScope merchantScope;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("MMM d, yyyy h:mma");

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Page<ActivityDto> index(Map<String, String> params) {
        int page = Integer.parseInt(params.getOrDefault("page", "1")) - 1;
        int limit = Integer.parseInt(params.getOrDefault("limit", "15"));

        StringBuilder where = new StringBuilder("WHERE 1=1");
        List<Object> queryParams = new ArrayList<>();
        int paramIndex = 1;

        String search = params.get("search");
        if (search != null && !search.isBlank()) {
            String[] words = search.toLowerCase().trim().split("\\s+");
            String combined = "LOWER(COALESCE(al.action, '') || ' ' || COALESCE(al.path, '') || ' ' || COALESCE(al.user_name, ''))";
            where.append(" AND (");
            for (int i = 0; i < words.length; i++) {
                if (i > 0)
                    where.append(" AND ");
                where.append(combined).append(" LIKE ?").append(paramIndex++);
                queryParams.add("%" + words[i] + "%");
            }
            where.append(")");
        }

        // Merchant-scoped: only show activities for the current merchant
        Long merchantId = merchantScope.merchantId();
        if (merchantId == null) {
            where.append(" AND 1=0");
        } else {
            where.append(" AND al.merchant_id = ?").append(paramIndex++);
            queryParams.add(merchantId);
        }

        String sql = """
                SELECT al.id, al.action, al.path, al.user_name,
                       'Terminal' as actionable_type, NULL as actionable_id, al.created_at
                FROM merchant.audit_logs al
                """ + where + " ORDER BY al.created_at DESC";

        String countSql = "SELECT COUNT(*) FROM merchant.audit_logs al " + where;

        Query countQuery = entityManager.createNativeQuery(countSql);
        Query dataQuery = entityManager.createNativeQuery(sql);

        for (int i = 0; i < queryParams.size(); i++) {
            countQuery.setParameter(i + 1, queryParams.get(i));
            dataQuery.setParameter(i + 1, queryParams.get(i));
        }

        long total = ((Number) countQuery.getSingleResult()).longValue();
        dataQuery.setFirstResult(page * limit);
        dataQuery.setMaxResults(limit);

        List<Object[]> rows = dataQuery.getResultList();
        List<ActivityDto> dtos = rows.stream().map(row -> {
            LocalDateTime createdAt = null;
            if (row[6] != null) {
                if (row[6] instanceof Timestamp ts)
                    createdAt = ts.toLocalDateTime();
                else if (row[6] instanceof LocalDateTime ldt)
                    createdAt = ldt;
                else if (row[6] instanceof java.time.OffsetDateTime odt)
                    createdAt = odt.toLocalDateTime();
            }

            String path = row[2] != null ? row[2].toString() : null;
            String actionableType = extractActionableType(path);
            Long actionableId = extractActionableId(path);

            return ActivityDto.builder().id(((Number) row[0]).longValue())
                    .action(row[1] != null ? row[1].toString() : null).description(path) // Use path as description
                    .adminName(row[3] != null ? row[3].toString() : null).actionableType(actionableType)
                    .actionableId(actionableId).createdAt(createdAt).build();
        }).toList();

        return new PageImpl<>(dtos, PageRequest.of(page, limit), total);
    }

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Transactional(readOnly = true)
    public Map<String, Object> show(Long id) {
        // Merchant-scoped: only allow viewing activities from the same merchant
        Long merchantId = merchantScope.merchantId();
        String scopeClause = "";
        if (merchantId == null) {
            scopeClause = " AND 1=0";
        } else {
            scopeClause = " AND al.merchant_id = :merchantId";
        }

        Query q = entityManager
                .createNativeQuery("SELECT al.id, al.action, al.path, al.user_id, al.user_name, al.user_email, "
                        + "al.user_role, al.ip_address, al.user_agent, al.response_status, al.created_at "
                        + "FROM merchant.audit_logs al " + "WHERE al.id = :id" + scopeClause);
        q.setParameter("id", id);
        if (merchantId != null) {
            q.setParameter("merchantId", merchantId);
        }

        Object[] r = (Object[]) q.getSingleResult();
        String path = r[2] != null ? r[2].toString() : null;

        java.util.LinkedHashMap<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("id", ((Number) r[0]).longValue());
        data.put("admin",
                r[3] != null
                        ? Map.of("id", ((Number) r[3]).longValue(), "name", r[4] != null ? r[4].toString() : null,
                                "email", r[5] != null ? r[5].toString() : null)
                        : null);
        data.put("admin_role", r[6] != null ? r[6].toString() : null);
        data.put("action", r[1] != null ? r[1].toString() : null);
        data.put("description", path);
        data.put("actionable_type", extractActionableType(path));
        data.put("actionable_id", extractActionableId(path));
        data.put("ip_address", r[7] != null ? r[7].toString() : null);
        data.put("user_agent", r[8] != null ? r[8].toString() : null);
        data.put("response_status", r[9] != null ? ((Number) r[9]).intValue() : null);
        if (r[10] != null) {
            LocalDateTime ldt;
            if (r[10] instanceof Timestamp ts)
                ldt = ts.toLocalDateTime();
            else if (r[10] instanceof java.time.OffsetDateTime odt)
                ldt = odt.toLocalDateTime();
            else
                ldt = (LocalDateTime) r[10];
            data.put("created_at", ldt.format(TIME_FORMAT));
        } else {
            data.put("created_at", null);
        }
        return data;
    }

    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getRecentActivities() {
        Long merchantId = merchantScope.merchantId();
        if (merchantId == null) {
            return List.of();
        }

        List<Object[]> rows = entityManager
                .createNativeQuery("SELECT al.action, al.path, al.created_at " + "FROM merchant.audit_logs al "
                        + "WHERE al.merchant_id = :merchantId " + "ORDER BY al.created_at DESC LIMIT 5")
                .setParameter("merchantId", merchantId).getResultList();

        return rows.stream().map(row -> {
            Map<String, Object> map = new HashMap<>();
            map.put("type", row[0] != null ? capitalize(row[0].toString()) : null);
            map.put("details", row[1] != null ? row[1].toString() : null);
            LocalDateTime createdAt = null;
            if (row[2] != null) {
                if (row[2] instanceof Timestamp ts)
                    createdAt = ts.toLocalDateTime();
                else if (row[2] instanceof LocalDateTime ldt)
                    createdAt = ldt;
                else if (row[2] instanceof java.time.OffsetDateTime odt)
                    createdAt = odt.toLocalDateTime();
            }
            map.put("date", createdAt != null ? createdAt.format(FMT) : null);
            return map;
        }).toList();
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty())
            return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    private String extractActionableType(String path) {
        if (path == null)
            return null;
        String[] parts = path.split("/");
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            if (!part.isEmpty() && !isNumeric(part) && !"api".equals(part)) {
                return capitalize(singularize(part));
            }
        }
        return null;
    }

    private Long extractActionableId(String path) {
        if (path == null)
            return null;
        String[] parts = path.split("/");
        for (String part : parts) {
            if (isNumeric(part)) {
                return Long.parseLong(part);
            }
        }
        return null;
    }

    private boolean isNumeric(String str) {
        try {
            Long.parseLong(str);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String singularize(String word) {
        if (word.endsWith("ies")) {
            return word.substring(0, word.length() - 3) + "y";
        }
        if (word.endsWith("s") && !word.endsWith("ss")) {
            return word.substring(0, word.length() - 1);
        }
        return word;
    }
}

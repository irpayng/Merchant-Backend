package com.tms.report.modules.activity.service;

import com.tms.report.core.security.MerchantScope;
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

    private static final DateTimeFormatter LIST_FMT = DateTimeFormatter.ofPattern("MMM d, yyyy h:mma");

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Page<Map<String, Object>> index(Map<String, String> params) {
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
                SELECT al.id, al.action, al.path, al.user_id, al.user_name, al.user_email, al.user_role,
                       CASE
                         WHEN al.path LIKE '/terminals%' THEN 'Terminals'
                         WHEN al.path LIKE '/transactions%' THEN 'Transactions'
                         WHEN al.path LIKE '/disputes%' THEN 'Disputes'
                         WHEN al.path LIKE '/settlements%' THEN 'Settlements'
                         WHEN al.path LIKE '/statements%' THEN 'Statements'
                         WHEN al.path LIKE '/merchant-users%' THEN 'Team'
                         WHEN al.path LIKE '/roles%' THEN 'Roles'
                         WHEN al.path LIKE '/settings%' THEN 'Settings'
                         WHEN al.path LIKE '/notifications%' THEN 'Notifications'
                         WHEN al.path LIKE '/auth%' THEN 'Authentication'
                         WHEN al.path LIKE '/profile%' THEN 'Profile'
                         ELSE 'Other'
                       END as module,
                       al.created_at
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
        List<Map<String, Object>> items = rows.stream().map(row -> {
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("id", ((Number) row[0]).longValue());

            String action = row[1] != null ? row[1].toString() : null;
            String userName = row[4] != null ? row[4].toString() : null;
            String module = row[7] != null ? row[7].toString() : null;

            item.put("action", action);
            item.put("module", module);
            item.put("description", buildDescription(userName, action));

            // User object for the table
            if (row[3] != null) {
                item.put("user", Map.of("id", ((Number) row[3]).longValue(), "name", userName != null ? userName : "",
                        "email", row[5] != null ? row[5].toString() : ""));
            }
            item.put("admin_name", userName);

            // Format date for display
            LocalDateTime createdAt = parseDateTime(row[8]);
            item.put("created_at", createdAt != null ? createdAt.format(LIST_FMT) : null);

            return item;
        }).toList();

        return new PageImpl<>(items, PageRequest.of(page, limit), total);
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
                        + "al.user_role, al.ip_address, al.user_agent, al.response_status, al.created_at, " + "CASE "
                        + "  WHEN al.path LIKE '/terminals%' THEN 'Terminals' "
                        + "  WHEN al.path LIKE '/transactions%' THEN 'Transactions' "
                        + "  WHEN al.path LIKE '/disputes%' THEN 'Disputes' "
                        + "  WHEN al.path LIKE '/settlements%' THEN 'Settlements' "
                        + "  WHEN al.path LIKE '/statements%' THEN 'Statements' "
                        + "  WHEN al.path LIKE '/merchant-users%' THEN 'Team' "
                        + "  WHEN al.path LIKE '/roles%' THEN 'Roles' "
                        + "  WHEN al.path LIKE '/settings%' THEN 'Settings' "
                        + "  WHEN al.path LIKE '/notifications%' THEN 'Notifications' "
                        + "  WHEN al.path LIKE '/auth%' THEN 'Authentication' "
                        + "  WHEN al.path LIKE '/profile%' THEN 'Profile' " + "  ELSE 'Other' " + "END as module "
                        + "FROM merchant.audit_logs al " + "WHERE al.id = :id" + scopeClause);
        q.setParameter("id", id);
        if (merchantId != null) {
            q.setParameter("merchantId", merchantId);
        }

        Object[] r = (Object[]) q.getSingleResult();
        String path = r[2] != null ? r[2].toString() : null;
        String userName = r[4] != null ? r[4].toString() : null;
        String action = r[1] != null ? r[1].toString() : null;
        String module = r[11] != null ? r[11].toString() : null;

        java.util.LinkedHashMap<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("id", ((Number) r[0]).longValue());

        // User object
        if (r[3] != null) {
            data.put("user", Map.of("id", ((Number) r[3]).longValue(), "name", userName != null ? userName : "",
                    "email", r[5] != null ? r[5].toString() : ""));
            data.put("admin", Map.of("id", ((Number) r[3]).longValue(), "name", userName != null ? userName : "",
                    "email", r[5] != null ? r[5].toString() : ""));
        }
        data.put("admin_name", userName);
        data.put("admin_role", r[6] != null ? r[6].toString() : null);
        data.put("action", action);
        data.put("module", module);
        data.put("description", buildDescription(userName, action));
        data.put("path", path);
        data.put("ip_address", r[7] != null ? r[7].toString() : null);
        data.put("user_agent", r[8] != null ? r[8].toString() : null);
        data.put("response_status", r[9] != null ? ((Number) r[9]).intValue() : null);

        LocalDateTime ldt = parseDateTime(r[10]);
        data.put("created_at", ldt != null ? ldt.format(LIST_FMT) : null);

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
                else if (row[2] instanceof java.time.Instant inst)
                    createdAt = LocalDateTime.ofInstant(inst, java.time.ZoneId.systemDefault());
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

    private LocalDateTime parseDateTime(Object value) {
        if (value == null)
            return null;
        if (value instanceof Timestamp ts)
            return ts.toLocalDateTime();
        if (value instanceof LocalDateTime ldt)
            return ldt;
        if (value instanceof java.time.OffsetDateTime odt)
            return odt.toLocalDateTime();
        if (value instanceof java.time.Instant inst)
            return LocalDateTime.ofInstant(inst, java.time.ZoneId.systemDefault());
        return null;
    }

    private String buildDescription(String userName, String action) {
        String user = userName != null ? userName : "User";
        String act = action != null ? action.toLowerCase() : "performed action";
        return user + " performed " + act;
    }
}

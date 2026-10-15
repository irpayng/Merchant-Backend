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

    /** SQL CASE expression for deriving module from path. */
    private static final String MODULE_CASE_SQL = """
            CASE
              WHEN al.path LIKE '/terminals%' THEN 'Terminals'
              WHEN al.path LIKE '/transactions%' THEN 'Transactions'
              WHEN al.path LIKE '/disputes%' THEN 'Disputes'
              WHEN al.path LIKE '/settlements%' THEN 'Settlements'
              WHEN al.path LIKE '/settlement-accounts%' THEN 'Settlements'
              WHEN al.path LIKE '/settlement-config%' THEN 'Settlements'
              WHEN al.path LIKE '/statements%' THEN 'Statements'
              WHEN al.path LIKE '/merchant-users%' THEN 'Team'
              WHEN al.path LIKE '/invitations%' THEN 'Team'
              WHEN al.path LIKE '/roles%' THEN 'Roles & Privileges'
              WHEN al.path LIKE '/settings%' THEN 'Settings'
              WHEN al.path LIKE '/notifications%' THEN 'Notifications'
              WHEN al.path LIKE '/auth%' THEN 'Authentication'
              WHEN al.path LIKE '/profile%' THEN 'Profile'
              WHEN al.path LIKE '/branches%' THEN 'Branches'
              WHEN al.path LIKE '/virtual-accounts%' THEN 'Virtual Accounts'
              WHEN al.path LIKE '/products%' THEN 'Products'
              WHEN al.path LIKE '/device-activities%' THEN 'Device Activities'
              WHEN al.path LIKE '/dashboard%' THEN 'Dashboard'
              WHEN al.path LIKE '/otp%' THEN 'Authentication'
              WHEN al.path LIKE '/upload%' THEN 'File Upload'
              ELSE 'Other'
            END""";

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

        // Filter by action
        String action = params.get("action");
        if (action != null && !action.isBlank()) {
            where.append(" AND al.action = ?").append(paramIndex++);
            queryParams.add(action);
        }

        // Filter by date range
        String dateFrom = params.get("date_from");
        String dateTo = params.get("date_to");
        if (dateFrom != null && !dateFrom.isBlank()) {
            where.append(" AND al.created_at >= ?").append(paramIndex++);
            queryParams.add(java.sql.Timestamp.valueOf(dateFrom + " 00:00:00"));
        }
        if (dateTo != null && !dateTo.isBlank()) {
            where.append(" AND al.created_at <= ?").append(paramIndex++);
            queryParams.add(java.sql.Timestamp.valueOf(dateTo + " 23:59:59"));
        }

        String sql = "SELECT al.id, al.action, al.path, al.user_id, al.user_name, al.user_email, al.user_role, "
                + MODULE_CASE_SQL + " as module, al.created_at FROM merchant.audit_logs al " + where
                + " ORDER BY al.created_at DESC";

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

            String rowAction = row[1] != null ? row[1].toString() : null;
            String rowPath = row[2] != null ? row[2].toString() : null;
            String userName = row[4] != null ? row[4].toString() : null;
            String module = row[7] != null ? row[7].toString() : null;

            item.put("action", rowAction);
            item.put("module", module);
            item.put("description", buildDescription(userName, rowAction, rowPath, module));

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
                        + "al.user_role, al.ip_address, al.user_agent, al.response_status, al.created_at, "
                        + MODULE_CASE_SQL + " as module " + "FROM merchant.audit_logs al " + "WHERE al.id = :id"
                        + scopeClause);
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
        data.put("description", buildDescription(userName, action, path, module));
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

    private String buildDescription(String userName, String action, String path, String module) {
        String user = userName != null ? userName : "User";

        // Handle specific actions with better descriptions
        if (action != null) {
            String lowerAction = action.toLowerCase();

            // Authentication actions
            if (lowerAction.equals("login")) {
                return user + " logged into the dashboard";
            }
            if (lowerAction.equals("logout")) {
                return user + " logged out of the dashboard";
            }
            if (lowerAction.contains("password")) {
                return user + " changed their password";
            }
            if (lowerAction.contains("otp") || lowerAction.contains("verify")) {
                return user + " verified their identity";
            }

            // Notification actions
            if (lowerAction.contains("mark") && lowerAction.contains("read")) {
                if (path != null && path.contains("mark-all")) {
                    return user + " marked all notifications as read";
                }
                return user + " marked a notification as read";
            }

            // Settlement account actions
            if (path != null && path.contains("settlement-account")) {
                if (lowerAction.contains("create") || lowerAction.contains("add")) {
                    return user + " added a new settlement account";
                }
                if (lowerAction.contains("update") || lowerAction.contains("edit")) {
                    return user + " updated a settlement account";
                }
                if (lowerAction.contains("delete") || lowerAction.contains("remove")) {
                    return user + " removed a settlement account";
                }
                if (lowerAction.contains("request")) {
                    return user + " requested a new settlement account";
                }
                return user + " modified settlement account settings";
            }

            // Terminal actions
            if (path != null && path.contains("terminal")) {
                if (lowerAction.contains("lock")) {
                    return user + " locked a terminal";
                }
                if (lowerAction.contains("unlock")) {
                    return user + " unlocked a terminal";
                }
                if (lowerAction.contains("assign")) {
                    return user + " assigned a terminal";
                }
                if (lowerAction.contains("create") || lowerAction.contains("add")) {
                    return user + " added a new terminal";
                }
                if (lowerAction.contains("update") || lowerAction.contains("edit")) {
                    return user + " updated terminal configuration";
                }
                return user + " modified terminal settings";
            }

            // Team/User actions
            if (path != null && (path.contains("merchant-user") || path.contains("invitation"))) {
                if (lowerAction.contains("invite") || lowerAction.contains("create")) {
                    return user + " invited a new team member";
                }
                if (lowerAction.contains("update") || lowerAction.contains("edit")) {
                    return user + " updated a team member's profile";
                }
                if (lowerAction.contains("delete") || lowerAction.contains("remove")) {
                    return user + " removed a team member";
                }
                if (lowerAction.contains("resend")) {
                    return user + " resent an invitation";
                }
                return user + " modified team settings";
            }

            // Role actions
            if (path != null && path.contains("role")) {
                if (lowerAction.contains("create") || lowerAction.contains("add")) {
                    return user + " created a new role";
                }
                if (lowerAction.contains("update") || lowerAction.contains("edit")) {
                    return user + " updated role permissions";
                }
                if (lowerAction.contains("delete") || lowerAction.contains("remove")) {
                    return user + " deleted a role";
                }
                return user + " modified role settings";
            }

            // Branch actions
            if (path != null && path.contains("branch")) {
                if (lowerAction.contains("create") || lowerAction.contains("add")) {
                    return user + " created a new branch";
                }
                if (lowerAction.contains("update") || lowerAction.contains("edit")) {
                    return user + " updated branch details";
                }
                if (lowerAction.contains("delete") || lowerAction.contains("remove")) {
                    return user + " deleted a branch";
                }
                return user + " modified branch settings";
            }

            // Dispute actions
            if (path != null && path.contains("dispute")) {
                if (lowerAction.contains("create") || lowerAction.contains("raise")) {
                    return user + " raised a dispute";
                }
                if (lowerAction.contains("update") || lowerAction.contains("respond")) {
                    return user + " responded to a dispute";
                }
                if (lowerAction.contains("close") || lowerAction.contains("resolve")) {
                    return user + " resolved a dispute";
                }
                return user + " updated a dispute";
            }

            // Profile actions
            if (path != null && path.contains("profile")) {
                return user + " updated their profile";
            }

            // Settings actions
            if (path != null && path.contains("setting")) {
                return user + " updated account settings";
            }

            // Virtual account actions
            if (path != null && path.contains("virtual-account")) {
                if (lowerAction.contains("create") || lowerAction.contains("request")) {
                    return user + " requested a new virtual account";
                }
                return user + " modified virtual account settings";
            }

            // Generic action with module context
            if (module != null && !"Other".equals(module)) {
                return user + " performed " + formatAction(action) + " in " + module;
            }
        }

        // Fallback: use action if available, otherwise generic
        if (action != null && !action.isBlank()) {
            return user + " performed " + formatAction(action);
        }

        return user + " performed an action";
    }

    /**
     * Format an action string to be more readable (lowercase, replace
     * dashes/underscores with spaces).
     */
    private String formatAction(String action) {
        if (action == null)
            return "action";
        return action.toLowerCase().replace("-", " ").replace("_", " ");
    }

    /**
     * Get filter options for the audit log listing. Returns distinct actions for
     * the current merchant so the frontend can populate the Action dropdown.
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Map<String, Object> getFilters() {
        Long merchantId = merchantScope.merchantId();
        if (merchantId == null) {
            return Map.of("actions", List.of());
        }

        List<String> rows = entityManager
                .createNativeQuery("SELECT DISTINCT al.action FROM merchant.audit_logs al "
                        + "WHERE al.merchant_id = :merchantId AND al.action IS NOT NULL " + "ORDER BY al.action")
                .setParameter("merchantId", merchantId).getResultList();

        List<Map<String, String>> actions = rows.stream().filter(action -> action != null && !action.isBlank())
                .map(action -> Map.of("id", action, "name", action)).toList();

        return Map.of("actions", actions);
    }
}

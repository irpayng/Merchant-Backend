package com.tms.report.modules.activity.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ActivityService {

    private final ActivityRepository activityRepository;
    private final EntityManager entityManager;
    private final MerchantScope merchantScope;
    private final ObjectMapper objectMapper;

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

        // Include action which now contains entity context
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

            item.put("action", extractBaseAction(rowAction));
            item.put("module", module);
            item.put("description", buildDescription(userName, rowAction, rowPath));

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
                        + MODULE_CASE_SQL + " as module, al.request_body " + "FROM merchant.audit_logs al "
                        + "WHERE al.id = :id" + scopeClause);
        q.setParameter("id", id);
        if (merchantId != null) {
            q.setParameter("merchantId", merchantId);
        }

        Object[] r = (Object[]) q.getSingleResult();
        String path = r[2] != null ? r[2].toString() : null;
        String userName = r[4] != null ? r[4].toString() : null;
        String action = r[1] != null ? r[1].toString() : null;
        String module = r[11] != null ? r[11].toString() : null;
        String requestBody = r[12] != null ? r[12].toString() : null;

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
        data.put("action", extractBaseAction(action));
        data.put("module", module);
        data.put("description", buildDescription(userName, action, path));
        data.put("path", path);
        data.put("ip_address", r[7] != null ? r[7].toString() : null);
        data.put("user_agent", r[8] != null ? r[8].toString() : null);
        data.put("response_status", r[9] != null ? ((Number) r[9]).intValue() : null);

        // Parse and include changes from request body
        Map<String, Object> changes = parseChanges(requestBody);
        if (changes != null && !changes.isEmpty()) {
            data.put("changes", changes);
        }

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
            String action = row[0] != null ? row[0].toString() : null;
            map.put("type", extractBaseAction(action));
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

    /**
     * Extract the base action from action string that may contain entity reference.
     * e.g., "Update terminal: 12345678" -> "Update terminal" Also fixes common
     * typos like "branche" -> "branch"
     */
    private String extractBaseAction(String action) {
        if (action == null)
            return null;
        int colonIdx = action.indexOf(':');
        String baseAction = colonIdx > 0 ? action.substring(0, colonIdx).trim() : action;

        // Fix common typos in stored actions
        baseAction = fixActionTypos(baseAction);

        return baseAction;
    }

    /**
     * Fix common typos in action strings. This handles legacy data that was stored
     * with typos.
     */
    private String fixActionTypos(String action) {
        if (action == null) {
            return null;
        }
        // Fix "branche" -> "branch" (singularize bug)
        return action.replace("branche", "branch").replace("Branche", "Branch");
    }

    /**
     * Build description from action string. The action now includes entity context
     * from the filter, so we just need to format it nicely with the user name.
     * Converts verbs to past tense for better readability.
     */
    private String buildDescription(String userName, String action, String path) {
        String user = userName != null ? userName : "User";

        if (action == null || action.isBlank()) {
            return user + " performed an action";
        }

        // Fix typos in the action string
        action = fixActionTypos(action);

        // Handle special cases
        String lowerAction = action.toLowerCase();

        // Authentication
        if (lowerAction.contains("login") || (path != null && path.contains("/auth/login"))) {
            return user + " logged into the dashboard";
        }
        if (lowerAction.contains("logout")) {
            return user + " logged out of the dashboard";
        }

        // Check if action already has entity context (contains ":")
        if (action.contains(":")) {
            // Action format: "Update terminal: 12345678"
            // Description: "Nelo Limited updated terminal 12345678"
            String[] parts = action.split(":", 2);
            String baseAction = parts[0].trim();
            String entityRef = parts[1].trim();

            // Convert base action to past tense
            String pastTenseAction = convertToPastTense(baseAction);

            return user + " " + pastTenseAction + " " + entityRef;
        }

        // Convert action to past tense
        String pastTenseAction = convertToPastTense(action);

        // Fallback: just use the past tense action
        return user + " " + pastTenseAction;
    }

    /**
     * Convert action verbs to past tense for description. "Create branch" ->
     * "created branch" "Assign TID" -> "assigned TID"
     */
    private String convertToPastTense(String action) {
        if (action == null || action.isEmpty()) {
            return action;
        }

        String lowerAction = action.toLowerCase();

        // Common action verb mappings to past tense
        if (lowerAction.startsWith("assign ")) {
            return "assigned " + action.substring(7);
        }
        if (lowerAction.startsWith("remove ")) {
            return "removed " + action.substring(7);
        }
        if (lowerAction.startsWith("create ")) {
            return "created " + action.substring(7);
        }
        if (lowerAction.startsWith("update ")) {
            return "updated " + action.substring(7);
        }
        if (lowerAction.startsWith("delete ")) {
            return "deleted " + action.substring(7);
        }
        if (lowerAction.startsWith("clear ")) {
            return "cleared " + action.substring(6);
        }
        if (lowerAction.startsWith("lock ")) {
            return "locked " + action.substring(5);
        }
        if (lowerAction.startsWith("unlock ")) {
            return "unlocked " + action.substring(7);
        }
        if (lowerAction.startsWith("invite ")) {
            return "invited " + action.substring(7);
        }
        if (lowerAction.startsWith("deactivate ")) {
            return "deactivated " + action.substring(11);
        }
        if (lowerAction.startsWith("activate ")) {
            return "activated " + action.substring(9);
        }
        if (lowerAction.startsWith("request ")) {
            return "requested " + action.substring(8);
        }
        if (lowerAction.startsWith("mark ")) {
            return "marked " + action.substring(5);
        }

        return action.toLowerCase();
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

    /**
     * Parse the request body JSON and extract meaningful changes for the detail
     * view.
     */
    private Map<String, Object> parseChanges(String requestBody) {
        if (requestBody == null || requestBody.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> body = objectMapper.readValue(requestBody, new TypeReference<Map<String, Object>>() {
            });
            // Filter out sensitive/internal fields
            body.remove("password");
            body.remove("pin");
            body.remove("otp");
            body.remove("token");
            body.remove("secret");
            return body.isEmpty() ? null : body;
        } catch (Exception e) {
            log.debug("Failed to parse request body as JSON: {}", e.getMessage());
            return null;
        }
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

        // Extract base action (before the colon) for filtering
        List<String> rows = entityManager.createNativeQuery(
                "SELECT DISTINCT CASE WHEN position(':' in al.action) > 0 THEN substring(al.action from 1 for position(':' in al.action) - 1) ELSE al.action END "
                        + "FROM merchant.audit_logs al "
                        + "WHERE al.merchant_id = :merchantId AND al.action IS NOT NULL " + "ORDER BY 1")
                .setParameter("merchantId", merchantId).getResultList();

        List<Map<String, String>> actions = rows.stream()
                .filter(action -> action != null && !action.toString().isBlank())
                .map(action -> Map.of("id", action.toString().trim(), "name", action.toString().trim())).toList();

        return Map.of("actions", actions);
    }
}

package com.tms.report.modules.user.controller;

import com.tms.report.core.dto.ApiResponse;
import com.tms.report.core.dto.PagedResponse;
import com.tms.report.core.filter.QueryFilterHelper;
import com.tms.report.modules.activity.annotation.LogActivity;
import com.tms.report.modules.grpc.service.ConfigHttpClient;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Cross-aggregator admin endpoints for the {@code /aggregator-uploads} page.
 * Lists every dispatched (aggregator, serial) row in one paginated view and
 * accepts uploads where the aggregator id is supplied as a form field instead
 * of as a path segment.
 *
 * <p>
 * Per-aggregator endpoints stay in {@link AggregatorTerminalController}; that
 * controller's class-level mapping is {@code /aggregators}, which is why this
 * lives in its own class.
 */
@RestController
@RequestMapping("/aggregator-uploads")
@RequiredArgsConstructor
public class AggregatorUploadController {

    private final EntityManager entityManager;
    private final ConfigHttpClient configHttpClient;

    /**
     * GET /aggregator-uploads — paginated listing of every dispatched serial across
     * all aggregators. Reads from the locally replicated
     * {@code aggregator_terminals} table joined to {@code users} and
     * {@code profiles} so the page can show the aggregator's display name.
     *
     * <p>
     * Filters: {@code search} (matches serial or aggregator name/email/phone),
     * {@code aggregator_id} (exact), date range via {@code dates[0]/[1]}.
     */
    @GetMapping
    @SuppressWarnings("unchecked")
    public Map<String, Object> index(@RequestParam Map<String, String> params, HttpServletRequest req) {
        int page = Integer.parseInt(params.getOrDefault("page", "1")) - 1;
        int limit = Integer.parseInt(params.getOrDefault("limit", "15"));

        StringBuilder where = new StringBuilder("WHERE 1=1");
        Map<String, Object> qp = new HashMap<>();

        // Search across serial and aggregator identity. The applySearch helper
        // OR's the columns, which is what we want.
        QueryFilterHelper.applySearch(where, qp, params, "at.serial", "u.email", "u.phone_number",
                "TRIM(COALESCE(p.first_name,'') || ' ' || COALESCE(p.last_name,''))");
        QueryFilterHelper.apply(where, qp, params, "at", Map.of("aggregator_id", "aggregator_id"));
        QueryFilterHelper.applyDates(where, qp, params, "at.created_at");

        String sql = """
                SELECT at.id, at.aggregator_id, at.serial, at.created_at,
                       u.email, u.phone_number,
                       TRIM(COALESCE(p.first_name,'') || ' ' || COALESCE(p.last_name,'')) AS aggregator_name
                FROM aggregator_terminals at
                LEFT JOIN users u ON u.id = at.aggregator_id
                LEFT JOIN profiles p ON p.user_id = at.aggregator_id
                """ + where + " ORDER BY at.created_at DESC";

        String countSql = "SELECT COUNT(*) FROM aggregator_terminals at "
                + "LEFT JOIN users u ON u.id = at.aggregator_id "
                + "LEFT JOIN profiles p ON p.user_id = at.aggregator_id " + where;

        Query countQuery = entityManager.createNativeQuery(countSql);
        Query dataQuery = entityManager.createNativeQuery(sql);
        qp.forEach(countQuery::setParameter);
        qp.forEach(dataQuery::setParameter);

        long total = ((Number) countQuery.getSingleResult()).longValue();
        dataQuery.setFirstResult(page * limit);
        dataQuery.setMaxResults(limit);

        List<Object[]> rows = dataQuery.getResultList();
        List<Map<String, Object>> dtos = rows.stream().map(row -> {
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("id", ((Number) row[0]).longValue());
            dto.put("aggregator_id", row[1] != null ? ((Number) row[1]).longValue() : null);
            dto.put("serial", str(row[2]));
            dto.put("created_at", row[3] != null ? row[3].toString() : null);
            String email = str(row[4]);
            String phone = str(row[5]);
            String name = str(row[6]);
            String display = name != null && !name.isBlank() ? name : (email != null ? email : null);
            dto.put("aggregator_name", display);
            dto.put("aggregator_email", email);
            dto.put("aggregator_phone", phone);
            return dto;
        }).toList();

        Page<Map<String, Object>> pagedResult = new PageImpl<>(dtos, PageRequest.of(page, limit), total);

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("stats", uploadsStats());

        return PagedResponse.from(pagedResult, "/aggregator-uploads", extra);
    }

    /**
     * Aggregate counts surfaced as a hero strip on the uploads page.
     */
    private Map<String, Object> uploadsStats() {
        try {
            Object[] row = (Object[]) entityManager.createNativeQuery("""
                    SELECT COUNT(*) AS total_serials,
                           COUNT(DISTINCT aggregator_id) AS aggregators,
                           COUNT(*) FILTER (WHERE created_at >= NOW() - INTERVAL '7 days') AS last_7d,
                           COUNT(*) FILTER (WHERE created_at >= NOW() - INTERVAL '30 days') AS last_30d
                    FROM aggregator_terminals
                    """).getSingleResult();
            long boundAgents = ((Number) entityManager
                    .createNativeQuery("SELECT COUNT(*) FROM users WHERE parent_id IS NOT NULL AND type = 'agent'")
                    .getSingleResult()).longValue();
            return Map.of("total_serials", ((Number) row[0]).longValue(), "aggregators", ((Number) row[1]).longValue(),
                    "last_7d", ((Number) row[2]).longValue(), "last_30d", ((Number) row[3]).longValue(), "bound_agents",
                    boundAgents);
        } catch (Exception e) {
            return Map.of("total_serials", 0, "aggregators", 0, "last_7d", 0, "last_30d", 0, "bound_agents", 0);
        }
    }

    /**
     * POST /aggregator-uploads — accepts the same multipart payload as the
     * per-aggregator upload but reads {@code aggregator_id} from the form so the
     * admin page doesn't need to put the aggregator id in the URL. Forwards to
     * config-service via the same code path used by the per-aggregator endpoint.
     */
    @LogActivity(action = "dispatch", description = "{admin} dispatched terminals to aggregator")
    @PostMapping(consumes = "multipart/form-data")
    public ApiResponse<Map<String, Object>> upload(@RequestParam("file") MultipartFile file,
            @RequestParam("aggregator_id") Long aggregatorId) {
        if (file.isEmpty()) {
            throw new RuntimeException("File is required");
        }
        String filename = file.getOriginalFilename();
        if (filename == null
                || (!filename.endsWith(".xlsx") && !filename.endsWith(".xls") && !filename.endsWith(".csv"))) {
            throw new RuntimeException("File must be xlsx, xls, or csv");
        }
        Map<String, Object> result = configHttpClient.uploadAggregatorTerminals(file, aggregatorId);
        Object data = result.get("data");
        if (data instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return ApiResponse.success(typed);
        }
        return ApiResponse.success(Map.of("ok", true));
    }

    /**
     * POST /aggregator-uploads/single — dispatch a single serial without a CSV.
     * Body: {@code {"serial": "...", "aggregator_id": 123}}. Forwards to
     * config-service's {@code POST /aggregator-terminals} which is idempotent
     * (existing dispatch to the same aggregator returns the row unchanged; existing
     * dispatch to a different aggregator is overwritten).
     */
    @LogActivity(action = "dispatch", description = "{admin} dispatched a serial to an aggregator")
    @PostMapping(value = "/single", consumes = "application/json")
    public ApiResponse<Map<String, Object>> assignOne(
            @org.springframework.web.bind.annotation.RequestBody Map<String, Object> body) {
        Object serialObj = body == null ? null : body.get("serial");
        Object aggregatorIdObj = body == null ? null : body.get("aggregator_id");
        if (serialObj == null || serialObj.toString().isBlank()) {
            throw new RuntimeException("Serial is required");
        }
        if (aggregatorIdObj == null) {
            throw new RuntimeException("aggregator_id is required");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("serial", serialObj.toString().trim());
        payload.put("aggregator_id", aggregatorIdObj);

        Map<String, Object> result = configHttpClient.postJson("/aggregator-terminals", payload);
        Object data = result.get("data");
        if (data instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return ApiResponse.success(typed);
        }
        return ApiResponse.success(Map.of("ok", true));
    }

    private static String str(Object o) {
        return o != null ? o.toString() : null;
    }
}

package com.tms.report.modules.user.controller;

import com.tms.report.core.dto.ApiResponse;
import com.tms.report.modules.activity.annotation.LogActivity;
import com.tms.report.modules.grpc.service.ConfigHttpClient;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * Admin endpoints for the aggregator → terminal dispatch step. The system stock
 * import lives at {@code /terminals} (TerminalController). This controller only
 * manages the {@code aggregator_terminals} pivot, owned by config-service but
 * replicated locally for fast reads.
 */
@RestController
@RequestMapping("/aggregators")
@RequiredArgsConstructor
public class AggregatorTerminalController {

    private final ConfigHttpClient configHttpClient;
    private final EntityManager entityManager;

    /**
     * GET /aggregators/{id}/terminals — list serials currently dispatched to an
     * aggregator. Reads from the locally replicated {@code aggregator_terminals}
     * table and enriches each row with the agent currently in custody — joined via
     * {@code terminals.user_id}, populated when the agent prepps the device.
     * Paginates client-side so the per-aggregator detail page can render thousands
     * of devices without a separate query for each.
     */
    @GetMapping("/{id}/terminals")
    @SuppressWarnings("unchecked")
    public ApiResponse<Map<String, Object>> listTerminals(@PathVariable Long id) {
        Query countQ = entityManager
                .createNativeQuery("SELECT COUNT(*) FROM aggregator_terminals WHERE aggregator_id = :id");
        countQ.setParameter("id", id);
        long total = ((Number) countQ.getSingleResult()).longValue();

        Query q = entityManager.createNativeQuery("""
                SELECT at.id, at.serial, at.aggregator_id, at.created_at,
                       t.user_id AS agent_id,
                       u.email AS agent_email, u.phone_number AS agent_phone,
                       TRIM(COALESCE(p.first_name, '') || ' ' || COALESCE(p.last_name, '')) AS agent_name,
                       t.locked, t.last_seen_at
                FROM aggregator_terminals at
                LEFT JOIN terminals t ON t.serial = at.serial
                LEFT JOIN users u ON u.id = t.user_id
                LEFT JOIN profiles p ON p.user_id = t.user_id
                WHERE at.aggregator_id = :id
                ORDER BY at.created_at DESC
                """);
        q.setParameter("id", id);
        List<Object[]> rows = q.getResultList();

        List<Map<String, Object>> items = rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", ((Number) r[0]).longValue());
            m.put("serial", r[1] != null ? r[1].toString() : null);
            m.put("aggregator_id", r[2] != null ? ((Number) r[2]).longValue() : null);
            m.put("created_at", r[3] != null ? r[3].toString() : null);

            Long agentId = r[4] != null ? ((Number) r[4]).longValue() : null;
            String agentEmail = r[5] != null ? r[5].toString() : null;
            String agentPhone = r[6] != null ? r[6].toString() : null;
            String rawName = r[7] != null ? r[7].toString().trim() : null;
            String displayName = rawName != null && !rawName.isBlank() ? rawName : agentEmail;

            m.put("agent_id", agentId);
            m.put("agent_name", displayName);
            m.put("agent_email", agentEmail);
            m.put("agent_phone", agentPhone);
            m.put("locked", r[8] != null && ((Boolean) r[8]));
            m.put("last_seen_at", r[9] != null ? r[9].toString() : null);
            return m;
        }).toList();

        return ApiResponse.success(Map.of("aggregator_id", id, "total", total, "items", items));
    }

    /**
     * POST /aggregators/{id}/terminals/upload — bulk dispatch from a CSV/XLSX with
     * a {@code serial} column. Wraps the multipart forward to config-service.
     */
    @LogActivity(action = "dispatch", description = "{admin} dispatched terminals to aggregator")
    @PostMapping(value = "/{id}/terminals/upload", consumes = "multipart/form-data")
    public ApiResponse<Map<String, Object>> upload(@PathVariable Long id, @RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw new RuntimeException("File is required");
        }
        String filename = file.getOriginalFilename();
        if (filename == null
                || (!filename.endsWith(".xlsx") && !filename.endsWith(".xls") && !filename.endsWith(".csv"))) {
            throw new RuntimeException("File must be xlsx, xls, or csv");
        }
        Map<String, Object> result = configHttpClient.uploadAggregatorTerminals(file, id);
        Object data = result.get("data");
        if (data instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return ApiResponse.success(typed);
        }
        return ApiResponse.success(Map.of("ok", true));
    }

    /**
     * DELETE /aggregators/{id}/terminals/{serial} — reclaim a serial from this
     * aggregator. The path takes the aggregator id for audit clarity even though
     * config-service only needs the serial.
     */
    @LogActivity(action = "reclaim", description = "{admin} reclaimed terminal from aggregator")
    @DeleteMapping("/{id}/terminals/{serial}")
    public ApiResponse<Map<String, Object>> unassign(@PathVariable Long id, @PathVariable String serial) {
        Map<String, Object> result = configHttpClient.deleteJson("/aggregator-terminals/" + serial);
        Object data = result.get("data");
        if (data instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return ApiResponse.success(typed);
        }
        return ApiResponse.success(Map.of("ok", true));
    }

    /**
     * GET /aggregators/{id}/agents — children of an aggregator (users whose
     * {@code parent_id} points at this aggregator). Read-only and served from the
     * local replicated {@code users} table; safe to call frequently.
     *
     * <p>
     * Each agent row carries the count of terminals currently in their custody (via
     * {@code terminals.user_id}) plus their lifetime transaction count and volume
     * so the per-aggregator detail page can show fleet activity at a glance without
     * round-tripping for each agent.
     */
    @GetMapping("/{id}/agents")
    @SuppressWarnings("unchecked")
    public ApiResponse<Map<String, Object>> listAgents(@PathVariable Long id) {
        Query countQ = entityManager.createNativeQuery("SELECT COUNT(*) FROM users WHERE parent_id = :id");
        countQ.setParameter("id", id);
        long total = ((Number) countQ.getSingleResult()).longValue();

        Query q = entityManager.createNativeQuery("""
                SELECT u.id, u.email, u.phone_number, u.type,
                       TRIM(COALESCE(p.first_name, '') || ' ' || COALESCE(p.last_name, '')) as name,
                       u.created_at,
                       COALESCE((SELECT COUNT(*) FROM terminals t WHERE t.user_id = u.id), 0) AS terminal_count,
                       COALESCE((SELECT COUNT(*) FROM transactions tr WHERE tr.user_id = u.id), 0) AS tx_count,
                       COALESCE((SELECT SUM(amount) FROM transactions tr
                                 WHERE tr.user_id = u.id AND tr.status_code = 'completed'), 0) AS tx_volume,
                       (SELECT MAX(created_at) FROM transactions tr WHERE tr.user_id = u.id) AS last_tx_at
                FROM users u LEFT JOIN profiles p ON p.user_id = u.id
                WHERE u.parent_id = :id
                ORDER BY u.created_at DESC LIMIT 200
                """);
        q.setParameter("id", id);
        List<Object[]> rows = q.getResultList();

        List<Map<String, Object>> agents = rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", ((Number) r[0]).longValue());
            m.put("email", r[1] != null ? r[1].toString() : null);
            m.put("phone_number", r[2] != null ? r[2].toString() : null);
            m.put("type", r[3] != null ? r[3].toString() : null);
            String name = r[4] != null ? r[4].toString().trim() : null;
            m.put("name", name == null || name.isBlank() ? (r[1] != null ? r[1].toString() : null) : name);
            m.put("created_at", r[5] != null ? r[5].toString() : null);
            m.put("terminal_count", ((Number) r[6]).longValue());
            m.put("transaction_count", ((Number) r[7]).longValue());
            m.put("transaction_volume", r[8] != null ? ((Number) r[8]).doubleValue() : 0.0);
            m.put("last_transaction_at", r[9] != null ? r[9].toString() : null);
            return m;
        }).toList();

        return ApiResponse.success(Map.of("aggregator_id", id, "total", total, "items", agents));
    }

    /**
     * GET /aggregators/{id}/stats — analytics summary for the per-aggregator detail
     * page. Counts and aggregates come from the locally replicated tables; the
     * heavy lifting (transaction volume, commission earned) joins {@code wallets} +
     * {@code statements} the same way the user-detail endpoint does, scoped to the
     * aggregator's downstream agents.
     */
    @GetMapping("/{id}/stats")
    public ApiResponse<Map<String, Object>> stats(@PathVariable Long id) {
        long terminalsDispatched = ((Number) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM aggregator_terminals WHERE aggregator_id = :id")
                .setParameter("id", id).getSingleResult()).longValue();

        long terminalsInCustody = ((Number) entityManager.createNativeQuery("""
                SELECT COUNT(*) FROM terminals t
                JOIN aggregator_terminals at ON at.serial = t.serial
                WHERE at.aggregator_id = :id AND t.user_id IS NOT NULL
                """).setParameter("id", id).getSingleResult()).longValue();

        long agentsTotal = ((Number) entityManager.createNativeQuery("SELECT COUNT(*) FROM users WHERE parent_id = :id")
                .setParameter("id", id).getSingleResult()).longValue();

        long activeAgents30d = ((Number) entityManager.createNativeQuery("""
                SELECT COUNT(DISTINCT u.id) FROM users u
                JOIN transactions tr ON tr.user_id = u.id
                WHERE u.parent_id = :id AND tr.created_at >= NOW() - INTERVAL '30 days'
                """).setParameter("id", id).getSingleResult()).longValue();

        // Downstream transaction volume (completed, last 30 days). Captures fleet
        // activity rather than the aggregator's own (aggregators don't transact
        // themselves).
        Object[] txStats = (Object[]) entityManager.createNativeQuery("""
                SELECT COALESCE(COUNT(*), 0),
                       COALESCE(SUM(amount), 0),
                       COALESCE(COUNT(*) FILTER (WHERE created_at >= NOW() - INTERVAL '30 days'), 0),
                       COALESCE(SUM(amount) FILTER (WHERE created_at >= NOW() - INTERVAL '30 days'), 0)
                FROM transactions
                WHERE status_code = 'completed' AND user_id IN (SELECT id FROM users WHERE parent_id = :id)
                """).setParameter("id", id).getSingleResult();

        // Aggregator's own commission wallet inflow lifetime + last 30 days. This
        // is the share rebated to the aggregator from downstream agent activity,
        // routed via the commission-split rule in the wallet service.
        Object[] commission = (Object[]) entityManager.createNativeQuery("""
                SELECT COALESCE(SUM(s.amount), 0),
                       COALESCE(SUM(s.amount) FILTER (WHERE s.created_at >= NOW() - INTERVAL '30 days'), 0)
                FROM statements s
                JOIN wallets w ON w.id = s.wallet_id
                WHERE w.user_id = :id AND w.type = 'commission' AND s.type = 'credit'
                """).setParameter("id", id).getSingleResult();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("terminals_dispatched", terminalsDispatched);
        stats.put("terminals_in_custody", terminalsInCustody);
        stats.put("agents_total", agentsTotal);
        stats.put("active_agents_30d", activeAgents30d);
        stats.put("transactions_total", ((Number) txStats[0]).longValue());
        stats.put("volume_total", ((Number) txStats[1]).doubleValue());
        stats.put("transactions_30d", ((Number) txStats[2]).longValue());
        stats.put("volume_30d", ((Number) txStats[3]).doubleValue());
        stats.put("commission_total", ((Number) commission[0]).doubleValue());
        stats.put("commission_30d", ((Number) commission[1]).doubleValue());
        return ApiResponse.success(stats);
    }
}

package com.tms.report.modules.dashboard.service;

import com.tms.report.core.security.TenantScope;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Slim, per-bank-scoped overview for the super-merchant portal. Every figure is
 * restricted to the caller's bank via {@link TenantScope} — a bank sees only
 * its direct merchants' terminals/TIDs/transactions; a global (IRPay) user sees
 * everything. No platform finance (revenue/liquidity/ledger).
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final EntityManager entityManager;
    private final TenantScope tenantScope;

    private static final DateTimeFormatter LABEL_FMT = DateTimeFormatter.ofPattern("MMM d");
    private static final List<String> TREND_STATUSES = List.of("completed", "processing", "reversed");

    // ---------------------------------------------------------------------
    // Scope helpers
    // ---------------------------------------------------------------------

    /** Scope fragment for a query whose user-id column is {@code col}. */
    private String userScope(String col) {
        if (tenantScope.isGlobal()) {
            return "";
        }
        String bank = tenantScope.bankCode();
        if (bank == null || bank.isBlank()) {
            return " AND 1=0";
        }
        return " AND " + col + " IN (SELECT user_id FROM tids WHERE bank_code = :smBank)";
    }

    /** Scope fragment for the {@code tids} table (alias {@code td}). */
    private String tidScope(String alias) {
        if (tenantScope.isGlobal()) {
            return "";
        }
        String bank = tenantScope.bankCode();
        if (bank == null || bank.isBlank()) {
            return " AND 1=0";
        }
        return " AND " + alias + ".bank_code = :smBank";
    }

    private boolean bankBound() {
        String bank = tenantScope.bankCode();
        return !tenantScope.isGlobal() && bank != null && !bank.isBlank();
    }

    private Query bindScope(Query q) {
        if (bankBound()) {
            q.setParameter("smBank", tenantScope.bankCode());
        }
        return q;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getDashboardData(Map<String, String> params) {
        Period period = resolvePeriod(params);

        StatusAgg current = statusAgg(period.start, period.end);
        StatusAgg previous = statusAgg(period.previousStart, period.previousEnd);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("period", periodInfo(period));
        data.put("stats", getStats(current));
        data.put("deltas", getDeltas(current, previous));
        data.put("transaction_health", getTransactionHealth(current));
        data.put("terminals", getTerminalStats(period));
        data.put("charts", getCharts(period));
        data.put("top_terminals", topTerminals(period));
        return data;
    }

    // ---------------------------------------------------------------------
    // Stats
    // ---------------------------------------------------------------------

    private Map<String, Object> getStats(StatusAgg current) {
        Bucket completed = current.bucket("completed");
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total_processed_value", completed.amount);
        stats.put("total_transactions", completed.count);
        stats.put("total_merchants", scalar("SELECT COUNT(*) FROM users WHERE 1=1" + userScope("id")));
        stats.put("total_terminals", scalar("SELECT COUNT(*) FROM terminals WHERE 1=1" + userScope("user_id")));
        stats.put("total_tids", scalar("SELECT COUNT(*) FROM tids td WHERE 1=1" + tidScope("td")));
        return stats;
    }

    private Map<String, Object> getDeltas(StatusAgg current, StatusAgg previous) {
        Bucket cur = current.bucket("completed");
        Bucket prev = previous.bucket("completed");
        Map<String, Object> deltas = new LinkedHashMap<>();
        deltas.put("processed_value", pctChange(cur.amount, prev.amount));
        deltas.put("transactions", pctChange(cur.count, prev.count));
        return deltas;
    }

    // ---------------------------------------------------------------------
    // Transaction health
    // ---------------------------------------------------------------------

    private Map<String, Object> getTransactionHealth(StatusAgg current) {
        Bucket completed = current.bucket("completed");
        Bucket failed = current.bucket("failed");
        Bucket processing = current.bucket("processing");
        Bucket reversed = current.bucket("reversed");

        long total = completed.count + failed.count + processing.count + reversed.count;
        double denom = total > 0 ? total : 1;

        Map<String, Object> health = new LinkedHashMap<>();
        health.put("total_count", total);
        health.put("completed_count", completed.count);
        health.put("failed_count", failed.count);
        health.put("processing_count", processing.count);
        health.put("reversed_count", reversed.count);
        health.put("success_rate", round2(completed.count / denom * 100));
        health.put("failure_rate", round2(failed.count / denom * 100));
        health.put("reversal_rate", round2(reversed.count / denom * 100));
        health.put("pending_value", processing.amount);
        health.put("stuck_count", stuckCount());
        return health;
    }

    private long stuckCount() {
        Query q = entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM transactions WHERE status_code = 'processing' AND created_at < :cut"
                        + userScope("user_id"));
        q.setParameter("cut", LocalDateTime.now().minusHours(1));
        bindScope(q);
        return ((Number) q.getSingleResult()).longValue();
    }

    // ---------------------------------------------------------------------
    // Terminal estate
    // ---------------------------------------------------------------------

    private Map<String, Object> getTerminalStats(Period period) {
        Map<String, Object> terminals = new LinkedHashMap<>();
        terminals.put("total", scalar("SELECT COUNT(*) FROM terminals WHERE 1=1" + userScope("user_id")));
        terminals.put("assigned_tids", scalar("SELECT COUNT(*) FROM tids td WHERE 1=1" + tidScope("td")));
        Query q = entityManager.createNativeQuery("""
                SELECT COUNT(DISTINCT t.terminal_id)
                FROM transactions t
                WHERE t.terminal_id IS NOT NULL AND t.status_code = 'completed'
                  AND t.created_at >= :s AND t.created_at <= :e
                """ + userScope("t.user_id"));
        q.setParameter("s", period.start);
        q.setParameter("e", period.end);
        bindScope(q);
        terminals.put("transacting", ((Number) q.getSingleResult()).longValue());
        return terminals;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> topTerminals(Period period) {
        Query q = entityManager.createNativeQuery("""
                SELECT t.terminal_id, COUNT(*) as cnt, COALESCE(SUM(t.amount), 0) as total
                FROM transactions t
                WHERE t.terminal_id IS NOT NULL AND t.status_code = 'completed'
                  AND t.created_at >= :s AND t.created_at <= :e
                """ + userScope("t.user_id") + """
                 GROUP BY t.terminal_id
                ORDER BY total DESC
                LIMIT 10
                """);
        q.setParameter("s", period.start);
        q.setParameter("e", period.end);
        bindScope(q);
        List<Object[]> rows = q.getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("terminal_serial", row[0] != null ? row[0].toString() : "unknown");
            item.put("count", ((Number) row[1]).longValue());
            item.put("total", ((Number) row[2]).doubleValue());
            out.add(item);
        }
        return out;
    }

    // ---------------------------------------------------------------------
    // Charts
    // ---------------------------------------------------------------------

    private Map<String, Object> getCharts(Period period) {
        Map<String, Object> charts = new LinkedHashMap<>();
        charts.put("transactions_trend", transactionsTrend(period));
        charts.put("product_types", productTypes(period));
        return charts;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> transactionsTrend(Period period) {
        Query q = entityManager.createNativeQuery("""
                SELECT CAST(created_at AS date) as d, status_code, COALESCE(SUM(amount), 0)
                FROM transactions
                WHERE created_at >= :s AND created_at <= :e AND status_code IN ('completed', 'processing', 'reversed')
                """ + userScope("user_id") + """
                 GROUP BY CAST(created_at AS date), status_code
                ORDER BY d
                """);
        q.setParameter("s", period.start);
        q.setParameter("e", period.end);
        bindScope(q);
        List<Object[]> rows = q.getResultList();

        Map<String, Map<LocalDate, Double>> byStatus = new HashMap<>();
        java.util.TreeSet<LocalDate> buckets = new java.util.TreeSet<>();
        for (Object[] row : rows) {
            LocalDate bucket = toLocalDate(row[0]);
            String status = row[1] != null ? row[1].toString() : "unknown";
            double amt = ((Number) row[2]).doubleValue();
            buckets.add(bucket);
            byStatus.computeIfAbsent(status, k -> new HashMap<>()).merge(bucket, amt, Double::sum);
        }

        List<LocalDate> ordered = new ArrayList<>(buckets);
        List<String> categories = ordered.stream().map(d -> d.format(LABEL_FMT)).toList();

        List<Map<String, Object>> series = new ArrayList<>();
        for (String status : TREND_STATUSES) {
            Map<LocalDate, Double> bucketMap = byStatus.getOrDefault(status, Map.of());
            List<Double> seriesData = new ArrayList<>();
            for (LocalDate d : ordered) {
                seriesData.add(bucketMap.getOrDefault(d, 0.0));
            }
            series.add(Map.of("name", capitalize(status), "data", seriesData));
        }
        return Map.of("categories", categories, "series", series);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> productTypes(Period period) {
        Query q = entityManager.createNativeQuery("""
                SELECT t.product_id as id, p.name,
                       COALESCE(SUM(CASE WHEN t.status_code != 'failed' THEN t.amount ELSE 0 END), 0) as total
                FROM transactions t
                JOIN products p ON p.id = t.product_id
                WHERE t.created_at >= :s AND t.created_at <= :e
                """ + userScope("t.user_id") + """
                 GROUP BY t.product_id, p.name
                HAVING COALESCE(SUM(CASE WHEN t.status_code != 'failed' THEN t.amount ELSE 0 END), 0) > 0
                ORDER BY total DESC
                """);
        q.setParameter("s", period.start);
        q.setParameter("e", period.end);
        bindScope(q);
        List<Object[]> rows = q.getResultList();
        return rows.stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row[0] != null ? ((Number) row[0]).longValue() : null);
            item.put("name", row[1] != null ? row[1].toString() : "Unknown");
            item.put("total", ((Number) row[2]).doubleValue());
            return item;
        }).toList();
    }

    // ---------------------------------------------------------------------
    // Status aggregation
    // ---------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private StatusAgg statusAgg(LocalDateTime start, LocalDateTime end) {
        Query q = entityManager.createNativeQuery("""
                SELECT status_code, COUNT(*) as cnt, COALESCE(SUM(amount), 0) as amt
                FROM transactions
                WHERE created_at >= :s AND created_at <= :e
                """ + userScope("user_id") + """
                 GROUP BY status_code
                """);
        q.setParameter("s", start);
        q.setParameter("e", end);
        bindScope(q);
        List<Object[]> rows = q.getResultList();

        StatusAgg agg = new StatusAgg();
        for (Object[] row : rows) {
            String status = row[0] != null ? row[0].toString() : "unknown";
            Bucket b = new Bucket();
            b.count = ((Number) row[1]).longValue();
            b.amount = ((Number) row[2]).doubleValue();
            agg.buckets.put(status, b);
        }
        return agg;
    }

    // ---------------------------------------------------------------------
    // Period / helpers
    // ---------------------------------------------------------------------

    private long scalar(String sql) {
        Query q = entityManager.createNativeQuery(sql);
        bindScope(q);
        return ((Number) q.getSingleResult()).longValue();
    }

    private Period resolvePeriod(Map<String, String> params) {
        LocalDateTime start = parseDate(params.get("start_date"), true);
        LocalDateTime end = parseDate(params.get("end_date"), false);
        if (start == null && end == null) {
            start = LocalDate.now().withDayOfMonth(1).atStartOfDay();
            end = LocalDateTime.now();
        } else if (start == null) {
            start = end.toLocalDate().withDayOfMonth(1).atStartOfDay();
        } else if (end == null) {
            end = LocalDateTime.now();
        }
        Period period = new Period();
        period.start = start;
        period.end = end;
        Duration length = Duration.between(start, end);
        period.previousEnd = start;
        period.previousStart = start.minus(length);
        return period;
    }

    private Map<String, Object> periodInfo(Period period) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("start", period.start.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        info.put("end", period.end.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return info;
    }

    private LocalDateTime parseDate(String value, boolean startOfDay) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception e) {
            try {
                LocalDate d = LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
                return startOfDay ? d.atStartOfDay() : d.atTime(23, 59, 59);
            } catch (Exception ex) {
                return null;
            }
        }
    }

    private double pctChange(double current, double previous) {
        double change = previous == 0 ? (current > 0 ? 100 : 0) : (current - previous) / previous * 100;
        return round2(change);
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private LocalDate toLocalDate(Object o) {
        if (o instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        if (o instanceof LocalDate d) {
            return d;
        }
        return LocalDate.parse(o.toString());
    }

    private static class Period {
        LocalDateTime start;
        LocalDateTime end;
        LocalDateTime previousStart;
        LocalDateTime previousEnd;
    }

    private static class StatusAgg {
        final Map<String, Bucket> buckets = new HashMap<>();

        Bucket bucket(String status) {
            return buckets.getOrDefault(status, new Bucket());
        }
    }

    private static class Bucket {
        long count;
        double amount;
    }
}

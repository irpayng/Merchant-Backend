package com.tms.report.modules.dashboard.service;

import com.tms.report.core.security.MerchantScope;
import com.tms.report.modules.grpc.service.GrpcClient;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Slim, per-merchant overview for the merchant dashboard. Every figure is
 * restricted to the authenticated merchant via {@link MerchantScope} — the
 * business sees only its own terminals/TIDs/transactions. No platform finance
 * (revenue/liquidity/ledger).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final EntityManager entityManager;
    private final MerchantScope merchantScope;
    private final GrpcClient grpcClient;

    private static final DateTimeFormatter LABEL_FMT = DateTimeFormatter.ofPattern("MMM d");
    private static final List<String> TREND_STATUSES = List.of("completed", "processing", "reversed");
    /**
     * Product codes that represent inflows to the merchant's wallet. Used to filter
     * dashboard "Total Sales" metrics to show only money coming in. - purchase:
     * Card purchase at POS terminal (customer pays merchant) - virtual-funding:
     * Bank transfer to merchant's virtual account
     */
    private static final List<String> INFLOW_PRODUCT_CODES = List.of("purchase", "virtual-funding");

    // ---------------------------------------------------------------------
    // Scope helpers
    // ---------------------------------------------------------------------

    /**
     * Scope fragment for a query whose user-id column is {@code col} — locks it to
     * the authenticated merchant. Fails closed to an empty result set when there is
     * no merchant in context.
     */
    private String userScope(String col) {
        return merchantScope.merchantId() == null ? " AND 1=0" : " AND " + col + " = :smMerchant";
    }

    /**
     * Scope fragment for the {@code tids} table (alias {@code td}) — the merchant's
     * own TIDs.
     */
    private String tidScope(String alias) {
        return merchantScope.merchantId() == null ? " AND 1=0" : " AND " + alias + ".user_id = :smMerchant";
    }

    private boolean merchantBound() {
        return merchantScope.merchantId() != null;
    }

    private Query bindScope(Query q) {
        if (merchantBound()) {
            q.setParameter("smMerchant", merchantScope.merchantId());
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
        data.put("transaction_stats", getTransactionStats(current));
        data.put("deltas", getDeltas(current, previous));
        data.put("transaction_health", getTransactionHealth(current));
        data.put("terminals", getTerminalStats(period));
        data.put("charts", getCharts(period));
        data.put("top_terminals", topTerminals(period));

        try {
            data.put("alerts", getAlerts());
        } catch (Exception e) {
            data.put("alerts", List.of());
        }

        // Wallet balances
        try {
            data.put("wallet", getWalletData());
        } catch (Exception e) {
            log.warn("Failed to fetch wallet data: {}", e.getMessage());
            data.put("wallet", Map.of("main_balance", "0", "commission_balance", "0"));
        }

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
    // Transaction stats (shaped for frontend TransactionStats component)
    // ---------------------------------------------------------------------

    /**
     * Returns transaction stats shaped as the frontend expects: { total: {count,
     * total, percentage}, completed: {...}, failed: {...}, processing: {...},
     * reversed: {...} }
     */
    private Map<String, Object> getTransactionStats(StatusAgg current) {
        Bucket completed = current.bucket("completed");
        Bucket failed = current.bucket("failed");
        Bucket processing = current.bucket("processing");
        Bucket reversed = current.bucket("reversed");

        long totalCount = completed.count + failed.count + processing.count + reversed.count;
        double totalAmount = completed.amount + failed.amount + processing.amount + reversed.amount;
        double denom = totalCount > 0 ? totalCount : 1;

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", statBucket(totalCount, totalAmount, 0));
        stats.put("completed", statBucket(completed.count, completed.amount, round2(completed.count / denom * 100)));
        stats.put("failed", statBucket(failed.count, failed.amount, round2(failed.count / denom * 100)));
        stats.put("processing",
                statBucket(processing.count, processing.amount, round2(processing.count / denom * 100)));
        stats.put("reversed", statBucket(reversed.count, reversed.amount, round2(reversed.count / denom * 100)));
        return stats;
    }

    private Map<String, Object> statBucket(long count, double total, double percentage) {
        Map<String, Object> bucket = new LinkedHashMap<>();
        bucket.put("count", count);
        bucket.put("total", total);
        bucket.put("percentage", percentage);
        return bucket;
    }

    // ---------------------------------------------------------------------
    // Alerts (offline terminals, stuck transactions)
    // ---------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getAlerts() {
        List<Map<String, Object>> alerts = new ArrayList<>();

        // Offline terminals (not seen in the last 30 minutes)
        try {
            String sql = "SELECT t.serial, t.last_seen_at FROM terminals t WHERE t.active = true"
                    + " AND (t.last_seen_at IS NULL OR t.last_seen_at < :cut)" + userScope("t.user_id")
                    + " ORDER BY t.last_seen_at ASC NULLS FIRST LIMIT 10";
            Query q = entityManager.createNativeQuery(sql);
            q.setParameter("cut", LocalDateTime.now().minusMinutes(30));
            bindScope(q);
            List<Object[]> offlineRows = q.getResultList();
            for (Object[] row : offlineRows) {
                Map<String, Object> alert = new LinkedHashMap<>();
                alert.put("type", "terminal_offline");
                alert.put("details", "Terminal " + (row[0] != null ? row[0].toString() : "unknown") + " is offline");
                alert.put("date", row[1] != null ? row[1].toString() : null);
                alerts.add(alert);
            }
        } catch (Exception e) {
            // Don't let alerts query failure break the entire dashboard
        }

        // Stuck transactions (processing for more than 1 hour)
        try {
            long stuck = stuckCount();
            if (stuck > 0) {
                Map<String, Object> alert = new LinkedHashMap<>();
                alert.put("type", "stuck_transactions");
                alert.put("details", stuck + " transaction" + (stuck > 1 ? "s" : "") + " stuck in processing");
                alert.put("date", LocalDateTime.now().toString());
                alerts.add(alert);
            }
        } catch (Exception e) {
            // Don't let stuck count failure break the entire dashboard
        }

        return alerts;
    }

    // ---------------------------------------------------------------------
    // Wallet balances
    // ---------------------------------------------------------------------

    /**
     * Fetch wallet balances for the authenticated merchant from wallet-service.
     * Returns main (default) and commission wallet balances.
     */
    private Map<String, Object> getWalletData() {
        Long merchantId = merchantScope.merchantId();
        if (merchantId == null) {
            return Map.of("main_balance", "0", "commission_balance", "0");
        }
        Map<String, Object> balances = grpcClient.getUserBalances(merchantId);
        Map<String, Object> wallet = new LinkedHashMap<>();
        wallet.put("main_balance", balances.getOrDefault("main_balance", "0"));
        wallet.put("commission_balance", balances.getOrDefault("commission_balance", "0"));
        return wallet;
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
            item.put("total", ((Number) row[2]).longValue());
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
                  AND product_code IN (:inflowCodes)
                """ + userScope("user_id") + """
                 GROUP BY CAST(created_at AS date), status_code
                ORDER BY d
                """);
        q.setParameter("s", period.start);
        q.setParameter("e", period.end);
        q.setParameter("inflowCodes", INFLOW_PRODUCT_CODES);
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
            item.put("total", ((Number) row[2]).longValue());
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
                  AND product_code IN (:inflowCodes)
                """ + userScope("user_id") + """
                 GROUP BY status_code
                """);
        q.setParameter("s", start);
        q.setParameter("e", end);
        q.setParameter("inflowCodes", INFLOW_PRODUCT_CODES);
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

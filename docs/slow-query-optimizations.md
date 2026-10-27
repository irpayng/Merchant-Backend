# Slow Query Optimizations

This document describes the database query optimizations applied to the Merchant Backend and Super Merchant applications.

## Summary of Changes

### 1. Terminal Metrics - Loose Index Scan (HIGH PRIORITY)

**Problem:** The `findLatestPerSerial()` query used `SELECT DISTINCT ON (serial) *` which performs a full table scan and on-disk sort of the entire `terminal_metrics` table (an append-only telemetry table that grows without bound).

**Solution:** Replaced with a recursive CTE "loose index scan" pattern that:
- Walks the index one distinct serial at a time
- Uses `LATERAL` to pick each serial's newest row with `ORDER BY created_at DESC LIMIT 1`
- Cost scales with number of devices (~hundreds), not number of rows

**Files Changed:**
- `Merchant-Backend/.../terminal/repository/TerminalMetricRepository.java`
- `super-merchant/.../terminal/repository/TerminalMetricRepository.java`

**Required Index:**
```sql
CREATE INDEX idx_terminal_metrics_serial_created 
    ON terminal_metrics (serial, created_at DESC);
```

### 2. Dashboard Terminal Status - Correlated Subquery Elimination (HIGH PRIORITY)

**Problem:** The `terminalStatus()` method used correlated subqueries that executed per-row:
```sql
(SELECT MAX(created_at) FROM transactions WHERE terminal_id = tm.serial) as last_txn
```

**Solution:** Replaced with a single grouped subquery JOIN:
```sql
LEFT JOIN (
    SELECT terminal_id, MAX(created_at) as last_txn
    FROM transactions
    WHERE terminal_id IS NOT NULL
    GROUP BY terminal_id
) lt ON lt.terminal_id = tm.serial
```

**Files Changed:**
- `Merchant-Backend/.../dashboard/service/DashboardService.java`
- `super-merchant/.../dashboard/service/DashboardService.java`

### 3. Fleet Health - Single Query Aggregation (MEDIUM PRIORITY)

**Problem:** The `fleetHealth()` endpoint ran 4 separate queries, each doing a full `DISTINCT ON` scan:
- `countReporting()`
- `countLowBattery(20)`
- `countPrinterNotReady()`
- `countStale(cutoff)`

**Solution:** Now fetches all latest metrics in one optimized query and computes counts in memory:
```java
List<TerminalMetric> latest = terminalMetricRepository.findLatestPerSerial();
long lowBattery = latest.stream()
    .filter(m -> m.getBatteryPct() != null && m.getBatteryPct() < 20)
    .count();
```

**Files Changed:**
- `Merchant-Backend/.../terminal/controller/TerminalController.java`

### 4. Unbounded List Returns (MEDIUM PRIORITY)

**Problem:** `findByUserId()` returned unbounded `List<Terminal>` without pagination.

**Solution:** Added pagination with default limit of 100:
```java
@Query("SELECT t FROM Terminal t WHERE t.userId = :userId ORDER BY t.createdAt DESC")
List<Terminal> findByUserId(@Param("userId") Long userId, Pageable pageable);

default List<Terminal> findByUserId(Long userId) {
    return findByUserId(userId, PageRequest.of(0, 100));
}
```

**Files Changed:**
- `Merchant-Backend/.../terminal/repository/TerminalRepository.java`
- `super-merchant/.../terminal/repository/TerminalRepository.java`

## Indexes to Add

Run the migration script at:
`src/main/resources/db/migration/V2__add_performance_indexes.sql`

### Critical Indexes:
```sql
-- Terminal metrics loose index scan support
CREATE INDEX idx_terminal_metrics_serial_created 
    ON terminal_metrics (serial, created_at DESC);

-- Transaction dashboard queries
CREATE INDEX idx_transactions_created_status 
    ON transactions (created_at, status_code);

CREATE INDEX idx_transactions_terminal_created 
    ON transactions (terminal_id, created_at DESC) 
    WHERE terminal_id IS NOT NULL;
```

### Optional Trigram Indexes (for LIKE searches):

If `pg_trgm` extension is available:
```sql
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX idx_audit_logs_user_email_trgm 
    ON audit_logs USING gin (user_email gin_trgm_ops);
```

## Known Remaining Issues

### Leading Wildcard LIKE Searches
The following repositories use `LIKE '%search%'` patterns that cannot use B-tree indexes:
- `AuditLogRepository.searchByMerchant()` - searches user_name, user_email, action, path
- `DisputeRepository.searchByUserId()` - searches subject, transaction_reference  
- `DeviceActivityRepository.findFiltered()` - searches reference, description, action

**Mitigation options:**
1. Add `pg_trgm` GIN indexes (best performance)
2. Use full-text search with `tsvector` columns
3. Accept slower searches on these admin-facing endpoints

### Dashboard Multiple Queries
The dashboard still runs 10+ queries per request. Future optimization could:
1. Consolidate into fewer aggregation queries using FILTER clauses
2. Add Redis caching for summary statistics
3. Use materialized views refreshed periodically

## Testing

After applying these changes, monitor query performance using:
```sql
-- Enable query logging for slow queries (>100ms)
ALTER SYSTEM SET log_min_duration_statement = 100;
SELECT pg_reload_conf();

-- Check for sequential scans on large tables
SELECT schemaname, relname, seq_scan, idx_scan 
FROM pg_stat_user_tables 
WHERE relname IN ('terminal_metrics', 'transactions', 'activities')
ORDER BY seq_scan DESC;
```

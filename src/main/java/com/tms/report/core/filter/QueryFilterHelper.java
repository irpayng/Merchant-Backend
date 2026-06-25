package com.tms.report.core.filter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Reusable query filter helper that mirrors PHP's Filterable trait. Appends
 * WHERE clauses to a StringBuilder and populates query parameters.
 *
 * Usage: QueryFilterHelper.apply(where, qp, params, "t", Map.of( "status_code",
 * "status_code", "product_id", "product_id", "type", "type" ));
 * QueryFilterHelper.applyDates(where, qp, params, "t.created_at");
 * QueryFilterHelper.applySearch(where, qp, params, "u.email",
 * "u.phone_number");
 */
public class QueryFilterHelper {

    /**
     * Builds a copy of {@code params} with paging set for an {@code index()}-style
     * listing (1-based page). Used by the shared paged export driver so each call
     * can request a specific batch without mutating the caller's map.
     */
    public static Map<String, String> pageParams(Map<String, String> params, int zeroBasedPage, int size) {
        Map<String, String> p = new java.util.HashMap<>(params);
        p.put("page", String.valueOf(zeroBasedPage + 1));
        p.put("limit", String.valueOf(size));
        return p;
    }

    /**
     * Apply simple column = value filters.
     * 
     * @param where
     *            StringBuilder to append to
     * @param qp
     *            Map of named parameters
     * @param params
     *            Request params from frontend
     * @param tableAlias
     *            Table alias prefix (e.g., "t" for "t.column")
     * @param filterableColumns
     *            Map of param_name -> column_name
     */
    public static void apply(StringBuilder where, Map<String, Object> qp, Map<String, String> params, String tableAlias,
            Map<String, String> filterableColumns) {
        for (var entry : filterableColumns.entrySet()) {
            String paramName = entry.getKey();
            String columnName = entry.getValue();
            String value = params.get(paramName);
            if (value != null && !value.isBlank()) {
                String safeParam = paramName.replace(".", "_").replace("-", "_");
                String fullColumn = tableAlias != null && !tableAlias.isBlank()
                        ? tableAlias + "." + columnName
                        : columnName;
                where.append(" AND ").append(fullColumn).append(" = :f_").append(safeParam);
                try {
                    qp.put("f_" + safeParam, Long.parseLong(value));
                } catch (NumberFormatException e) {
                    if ("true".equals(value))
                        qp.put("f_" + safeParam, true);
                    else if ("false".equals(value))
                        qp.put("f_" + safeParam, false);
                    else
                        qp.put("f_" + safeParam, value);
                }
            }
        }
    }

    /**
     * Apply date range filter from dates[0]/dates[1], dates[] array, or
     * start_date/end_date params.
     */
    public static void applyDates(StringBuilder where, Map<String, Object> qp, Map<String, String> params,
            String dateColumn) {
        LocalDateTime start = parseDate(params.get("dates[0]"));
        if (start == null)
            start = parseDate(params.get("start_date"));
        LocalDateTime end = parseDate(params.get("dates[1]"));
        if (end == null)
            end = parseDate(params.get("end_date"));

        // Handle dates[] format (Spring joins repeated params with comma in some cases)
        if (start == null && end == null) {
            String datesRaw = params.get("dates[]");
            if (datesRaw != null && datesRaw.contains(",")) {
                String[] parts = datesRaw.split(",");
                start = parseDate(parts[0].trim());
                end = parts.length > 1 ? parseDate(parts[1].trim()) : null;
            } else if (datesRaw != null) {
                start = parseDate(datesRaw);
            }
        }

        if (start != null) {
            where.append(" AND ").append(dateColumn).append(" >= :fd_start");
            qp.put("fd_start", start);
        }
        if (end != null) {
            end = toEndOfDay(end);
            where.append(" AND ").append(dateColumn).append(" <= :fd_end");
            qp.put("fd_end", end);
        }
    }

    /**
     * Apply ILIKE search across multiple columns.
     */
    public static void applySearch(StringBuilder where, Map<String, Object> qp, Map<String, String> params,
            String... columns) {
        String search = params.get("search");
        if (search == null || search.isBlank())
            return;

        StringBuilder searchClause = new StringBuilder(" AND (");
        for (int i = 0; i < columns.length; i++) {
            if (i > 0)
                searchClause.append(" OR ");
            searchClause.append(columns[i]).append(" ILIKE :f_search");
        }
        searchClause.append(")");
        where.append(searchClause);
        qp.put("f_search", "%" + search + "%");
    }

    /**
     * Extract start and end dates from params, handling all formats:
     * dates[0]/dates[1], dates[], start_date/end_date
     */
    public static LocalDateTime[] extractDates(Map<String, String> params) {
        LocalDateTime start = parseDate(params.get("dates[0]"));
        if (start == null)
            start = parseDate(params.get("start_date"));
        LocalDateTime end = parseDate(params.get("dates[1]"));
        if (end == null)
            end = parseDate(params.get("end_date"));

        if (start == null && end == null) {
            String datesRaw = params.get("dates[]");
            if (datesRaw != null && datesRaw.contains(",")) {
                String[] parts = datesRaw.split(",");
                start = parseDate(parts[0].trim());
                end = parts.length > 1 ? parseDate(parts[1].trim()) : null;
            } else if (datesRaw != null) {
                start = parseDate(datesRaw);
            }
        }

        if (end != null) {
            end = toEndOfDay(end);
        }

        return new LocalDateTime[]{start, end};
    }

    /**
     * Parse a date string into LocalDateTime. Tries ISO datetime first, falls back
     * to date-only (start of day).
     */
    public static LocalDateTime parseDate(String value) {
        if (value == null || value.isBlank())
            return null;
        try {
            return LocalDateTime.parse(value);
        } catch (Exception e) {
            try {
                return LocalDate.parse(value).atStartOfDay();
            } catch (Exception e2) {
                return null;
            }
        }
    }

    /**
     * Adjust a date to end-of-day (23:59:59) if it's at start-of-day (00:00:00).
     */
    public static LocalDateTime toEndOfDay(LocalDateTime date) {
        if (date != null && date.getHour() == 0 && date.getMinute() == 0 && date.getSecond() == 0) {
            return date.withHour(23).withMinute(59).withSecond(59);
        }
        return date;
    }
}

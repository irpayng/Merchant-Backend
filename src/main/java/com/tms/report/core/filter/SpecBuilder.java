package com.tms.report.core.filter;

import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.data.jpa.domain.Specification;

/**
 * Builds JPA Specifications from request params for controllers using
 * repositories directly.
 */
public class SpecBuilder {

    /**
     * Build a Specification that filters by the given column mappings.
     * 
     * @param params
     *            Request params
     * @param filterableColumns
     *            Map of param_name -> entity_field_name
     */
    public static <T> Specification<T> fromParams(Map<String, String> params, Map<String, String> filterableColumns) {
        Specification<T> spec = (root, query, cb) -> cb.conjunction();

        for (var entry : filterableColumns.entrySet()) {
            String paramName = entry.getKey();
            String fieldName = entry.getValue();
            String value = params.get(paramName);
            if (value != null && !value.isBlank()) {
                spec = spec.and((root, query, cb) -> {
                    try {
                        return cb.equal(root.get(fieldName), Long.parseLong(value));
                    } catch (NumberFormatException e) {
                        if ("true".equals(value))
                            return cb.equal(root.get(fieldName), true);
                        if ("false".equals(value))
                            return cb.equal(root.get(fieldName), false);
                        return cb.equal(root.get(fieldName), value);
                    }
                });
            }
        }

        // Search
        String search = params.get("search");
        if (search != null && !search.isBlank()) {
            String pattern = "%" + search.toLowerCase() + "%";
            spec = spec.and((root, query, cb) -> {
                // Generic search - tries common fields
                try {
                    return cb.like(cb.lower(root.get("name")), pattern);
                } catch (Exception e) {
                    return cb.conjunction();
                }
            });
        }

        // Date filtering
        spec = applyDates(spec, params);

        return spec;
    }

    private static <T> Specification<T> applyDates(Specification<T> spec, Map<String, String> params) {
        String d0 = params.get("dates[0]");
        if (d0 == null)
            d0 = params.get("start_date");
        String d1 = params.get("dates[1]");
        if (d1 == null)
            d1 = params.get("end_date");

        if (d0 != null && !d0.isBlank()) {
            LocalDateTime start = QueryFilterHelper.parseDate(d0);
            if (start != null) {
                spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), start));
            }
        }
        if (d1 != null && !d1.isBlank()) {
            LocalDateTime end = QueryFilterHelper.toEndOfDay(QueryFilterHelper.parseDate(d1));
            if (end != null) {
                final LocalDateTime finalEnd = end;
                spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("createdAt"), finalEnd));
            }
        }

        return spec;
    }
}

package com.tms.report.core.specification;

import com.tms.report.core.filter.QueryFilterHelper;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.data.jpa.domain.Specification;

/**
 * Generic filter specification mirroring Laravel's Filterable trait. Builds JPA
 * Specification from request parameter maps.
 */
public class FilterSpecification<T> implements Specification<T> {

    private final Map<String, Object> filters;
    private final List<String> filterableFields;

    public FilterSpecification(Map<String, Object> filters, List<String> filterableFields) {
        this.filters = filters;
        this.filterableFields = filterableFields;
    }

    @Override
    public Predicate toPredicate(Root<T> root, CriteriaQuery<?> query, CriteriaBuilder cb) {
        List<Predicate> predicates = new ArrayList<>();

        for (Map.Entry<String, Object> entry : filters.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (value == null || value.toString().isBlank()) {
                continue;
            }

            if (filterableFields.contains(key)) {
                predicates.add(cb.equal(root.get(key), value));
            }
        }

        // Date range filter
        if (filters.containsKey("dates") && filters.get("dates") instanceof List<?> dates && dates.size() >= 2) {
            LocalDateTime start = LocalDateTime.parse(dates.get(0).toString());
            LocalDateTime end = QueryFilterHelper.toEndOfDay(LocalDateTime.parse(dates.get(1).toString()));
            predicates.add(cb.between(root.get("createdAt"), start, end));
        }

        return cb.and(predicates.toArray(new Predicate[0]));
    }

    /**
     * Create a search specification for ILIKE-style searching across multiple
     * fields.
     */
    public static <T> Specification<T> search(String search, List<String> searchableFields) {
        if (search == null || search.isBlank()) {
            return (root, query, cb) -> cb.conjunction();
        }
        return (root, query, cb) -> {
            String pattern = "%" + search.toLowerCase() + "%";
            List<Predicate> predicates = searchableFields.stream()
                    .map(field -> cb.like(cb.lower(root.get(field)), pattern)).toList();
            return cb.or(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * Date range specification.
     */
    public static <T> Specification<T> dateRange(LocalDateTime start, LocalDateTime end) {
        return (root, query, cb) -> cb.between(root.get("createdAt"), start, end);
    }
}

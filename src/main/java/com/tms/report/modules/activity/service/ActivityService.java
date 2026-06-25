package com.tms.report.modules.activity.service;

import com.tms.report.modules.activity.dto.ActivityDto;
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
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("MMM d, yyyy h:mma");

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Page<ActivityDto> index(Map<String, String> params) {
        int page = Integer.parseInt(params.getOrDefault("page", "1")) - 1;
        int limit = Integer.parseInt(params.getOrDefault("limit", "15"));

        StringBuilder where = new StringBuilder("WHERE 1=1");
        List<Object> queryParams = new ArrayList<>();
        int paramIndex = 1;

        String search = params.get("search");
        if (search != null && !search.isBlank()) {
            String[] words = search.toLowerCase().trim().split("\\s+");
            String combined = "LOWER(COALESCE(REGEXP_REPLACE(act.action, '([a-z])([A-Z])', '\\1 \\2', 'g'), '') || ' ' || COALESCE(act.description, '') || ' ' || COALESCE(a.name, ''))";
            where.append(" AND (");
            for (int i = 0; i < words.length; i++) {
                if (i > 0)
                    where.append(" AND ");
                where.append(combined).append(" LIKE ?").append(paramIndex++);
                queryParams.add("%" + words[i] + "%");
            }
            where.append(")");
        }

        String sql = """
                SELECT act.id, act.action, act.description, a.name as admin_name,
                       act.actionable_type, act.actionable_id, act.created_at
                FROM admin_activities act
                LEFT JOIN admins a ON a.id = act.admin_id
                """ + where + " ORDER BY act.created_at DESC";

        String countSql = "SELECT COUNT(*) FROM admin_activities act LEFT JOIN admins a ON a.id = act.admin_id "
                + where;

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
        List<ActivityDto> dtos = rows.stream().map(row -> {
            LocalDateTime createdAt = null;
            if (row[6] != null) {
                if (row[6] instanceof Timestamp ts)
                    createdAt = ts.toLocalDateTime();
                else if (row[6] instanceof LocalDateTime ldt)
                    createdAt = ldt;
            }

            return ActivityDto.builder().id(((Number) row[0]).longValue())
                    .action(row[1] != null ? row[1].toString() : null)
                    .description(row[2] != null ? row[2].toString() : null)
                    .adminName(row[3] != null ? row[3].toString() : null)
                    .actionableType(row[4] != null ? row[4].toString() : null)
                    .actionableId(row[5] != null ? ((Number) row[5]).longValue() : null).createdAt(createdAt).build();
        }).toList();

        return new PageImpl<>(dtos, PageRequest.of(page, limit), total);
    }

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Transactional(readOnly = true)
    public Map<String, Object> show(Long id) {
        Object[] r = (Object[]) entityManager.createNativeQuery(
                "SELECT act.id, act.action, act.description, act.actionable_type, act.actionable_id, act.created_at, "
                        + "a.id as admin_id, a.name as admin_name, a.email as admin_email "
                        + "FROM admin_activities act LEFT JOIN admins a ON a.id = act.admin_id " + "WHERE act.id = :id")
                .setParameter("id", id).getSingleResult();

        java.util.LinkedHashMap<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("id", ((Number) r[0]).longValue());
        data.put("admin",
                r[6] != null
                        ? Map.of("id", ((Number) r[6]).longValue(), "name", r[7] != null ? r[7].toString() : null,
                                "email", r[8] != null ? r[8].toString() : null)
                        : null);
        data.put("action", r[1] != null ? r[1].toString() : null);
        data.put("description", r[2] != null ? r[2].toString() : null);
        data.put("actionable_type", r[3] != null ? r[3].toString() : null);
        data.put("actionable_id", r[4] != null ? ((Number) r[4]).longValue() : null);
        if (r[5] != null) {
            LocalDateTime ldt = r[5] instanceof Timestamp ts ? ts.toLocalDateTime() : (LocalDateTime) r[5];
            data.put("created_at", ldt.format(TIME_FORMAT));
        } else {
            data.put("created_at", null);
        }
        return data;
    }

    public List<Map<String, Object>> getRecentActivities() {
        return activityRepository.findTop5ByOrderByCreatedAtDesc().stream().map(a -> {
            Map<String, Object> map = new HashMap<>();
            map.put("type", capitalize(a.getAction()));
            map.put("details", a.getDescription());
            map.put("date", a.getCreatedAt() != null ? a.getCreatedAt().format(FMT) : null);
            return map;
        }).toList();
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty())
            return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }
}

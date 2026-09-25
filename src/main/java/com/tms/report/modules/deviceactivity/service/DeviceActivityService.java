package com.tms.report.modules.deviceactivity.service;

import com.tms.report.core.filter.QueryFilterHelper;
import com.tms.report.core.security.MerchantScope;
import com.tms.report.modules.deviceactivity.model.DeviceActivity;
import com.tms.report.modules.deviceactivity.repository.DeviceActivityRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DeviceActivityService {

    private final DeviceActivityRepository deviceActivityRepository;
    private final EntityManager entityManager;
    private final MerchantScope merchantScope;

    private static final DateTimeFormatter LIST_FMT = DateTimeFormatter.ofPattern("MMM d, yyyy h:mma");

    // Pattern to extract operator name from description like "Operator John logged
    // in on terminal ABC123"
    private static final Pattern OPERATOR_PATTERN = Pattern.compile("Operator (.+?) logged");
    private static final Pattern MERCHANT_PATTERN = Pattern.compile("^(.+?) logged in on terminal");

    /**
     * Merchant id for scoping queries. Fails closed to -1 when there is no merchant
     * in context, so the estate is never leaked.
     */
    private Long merchantScopeId() {
        Long m = merchantScope.merchantId();
        return m != null ? m : -1L;
    }

    @Transactional(readOnly = true)
    public Page<Map<String, Object>> index(Map<String, String> params) {
        int page = Integer.parseInt(params.getOrDefault("page", "1")) - 1;
        int limit = Integer.parseInt(params.getOrDefault("limit", "15"));
        Long merchantId = merchantScopeId();

        // Search pattern
        String search = trimToNull(params.get("search"));
        String searchPattern = search != null ? "%" + search.toLowerCase() + "%" : null;

        // Filters
        String action = trimToNull(params.get("action"));
        String deviceSerial = trimToNull(params.get("device_serial"));
        Long operatorId = parseLongOrNull(params.get("operator_id"));

        // Date range
        LocalDateTime[] dates = QueryFilterHelper.extractDates(params);
        LocalDateTime dateFrom = dates[0];
        LocalDateTime dateTo = dates[1];

        var pageable = PageRequest.of(page, limit);
        Page<DeviceActivity> result = deviceActivityRepository.findFiltered(merchantId, searchPattern, action,
                deviceSerial, operatorId, dateFrom, dateTo, pageable);

        // Enrich with terminal info and operator names
        enrichWithTerminals(result.getContent());
        enrichWithOperatorNames(result.getContent());

        // Transform to response format
        return result.map(this::toResponseMap);
    }

    private Map<String, Object> toResponseMap(DeviceActivity da) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", da.getId());
        item.put("device_serial", da.getReference()); // reference stores device serial
        item.put("operator_id", da.getActionableId()); // actionable_id stores operator ID
        item.put("operator_name", da.getOperatorName() != null ? da.getOperatorName() : "Direct Login");
        item.put("action", da.getAction());
        item.put("action_label", formatAction(da.getAction()));
        item.put("description", da.getDescription());
        item.put("created_at", da.getCreatedAt() != null ? da.getCreatedAt().format(LIST_FMT) : null);

        // Terminal info
        if (da.getTerminal() != null) {
            item.put("terminal",
                    Map.of("id", da.getTerminal().getId(), "serial", da.getTerminal().getSerial(), "model",
                            da.getTerminal().getModel() != null ? da.getTerminal().getModel() : "", "make",
                            da.getTerminal().getMake() != null ? da.getTerminal().getMake() : ""));
        } else {
            item.put("terminal", Map.of("serial", da.getReference() != null ? da.getReference() : ""));
        }

        return item;
    }

    private String formatAction(String action) {
        if (action == null) {
            return "Unknown";
        }
        return switch (action.toLowerCase()) {
            case "login" -> "Logged In";
            case "logout" -> "Logged Out";
            default -> action.substring(0, 1).toUpperCase() + action.substring(1);
        };
    }

    @Transactional(readOnly = true)
    public Map<String, Object> show(Long id) {
        DeviceActivity da = deviceActivityRepository.findById(id)
                .orElseThrow(() -> new java.util.NoSuchElementException("Device activity not found"));

        // Verify merchant scope and actionable type
        Long merchantId = merchantScopeId();
        if (!merchantId.equals(da.getUserId()) || !"device_login".equals(da.getActionableType())) {
            throw new java.util.NoSuchElementException("Device activity not found");
        }

        enrichWithTerminals(List.of(da));
        enrichWithOperatorNames(List.of(da));
        return toDetailMap(da);
    }

    private Map<String, Object> toDetailMap(DeviceActivity da) {
        Map<String, Object> item = toResponseMap(da);
        item.put("merchant_id", da.getUserId());
        return item;
    }

    /**
     * Filter options for the UI dropdowns.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getFilters() {
        Long merchantId = merchantScopeId();
        Map<String, Object> filters = new LinkedHashMap<>();

        try {
            // Actions
            List<Map<String, String>> actions = new ArrayList<>();
            actions.add(Map.of("id", "login", "name", "Logged In"));
            actions.add(Map.of("id", "logout", "name", "Logged Out"));
            filters.put("actions", actions);

            // Devices
            List<String> serials = deviceActivityRepository.findDistinctDeviceSerials(merchantId);
            filters.put("devices", serials.stream().map(s -> Map.of("id", s, "name", s)).toList());

            // Operators - look up names from operators table
            List<Long> operatorIds = deviceActivityRepository.findDistinctOperatorIds(merchantId);
            List<Map<String, String>> operatorOptions = new ArrayList<>();
            if (!operatorIds.isEmpty()) {
                Map<Long, String> operatorNames = lookupOperatorNames(operatorIds);
                for (Long opId : operatorIds) {
                    String name = operatorNames.getOrDefault(opId, "Operator #" + opId);
                    operatorOptions.add(Map.of("id", opId.toString(), "name", name));
                }
            }
            filters.put("operators", operatorOptions);
        } catch (Exception e) {
            // Return empty filters on error
            filters.put("actions", List.of());
            filters.put("devices", List.of());
            filters.put("operators", List.of());
        }

        return filters;
    }

    /**
     * Dashboard summary stats.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getStats() {
        Long merchantId = merchantScopeId();
        Map<String, Object> stats = new LinkedHashMap<>();

        try {
            stats.put("total", deviceActivityRepository.countByMerchantId(merchantId));
            stats.put("recent_logins",
                    deviceActivityRepository.countRecentLogins(merchantId, LocalDateTime.now().minusHours(24)));
        } catch (Exception e) {
            stats.put("total", 0L);
            stats.put("recent_logins", 0L);
        }

        return stats;
    }

    /**
     * Enrich activities with terminal information from the terminals table.
     */
    @SuppressWarnings("unchecked")
    private void enrichWithTerminals(List<DeviceActivity> activities) {
        if (activities == null || activities.isEmpty()) {
            return;
        }

        List<String> serials = activities.stream().map(DeviceActivity::getReference)
                .filter(s -> s != null && !s.isBlank()).distinct().toList();

        if (serials.isEmpty()) {
            return;
        }

        try {
            String sql = "SELECT t.id, t.serial, t.model, t.make FROM terminals t WHERE t.serial IN :serials";
            List<Object[]> rows = entityManager.createNativeQuery(sql).setParameter("serials", serials).getResultList();

            Map<String, DeviceActivity.TerminalInfo> bySerial = new LinkedHashMap<>();
            for (Object[] row : rows) {
                Long id = row[0] != null ? ((Number) row[0]).longValue() : null;
                String serial = row[1] != null ? row[1].toString() : null;
                String model = row[2] != null ? row[2].toString() : null;
                String make = row[3] != null ? row[3].toString() : null;
                if (serial != null) {
                    bySerial.put(serial, new DeviceActivity.TerminalInfo(id, serial, model, make));
                }
            }

            for (DeviceActivity da : activities) {
                if (da.getReference() != null) {
                    da.setTerminal(bySerial.get(da.getReference()));
                }
            }
        } catch (Exception e) {
            // terminals table may not exist yet — leave activities unenriched
        }
    }

    /**
     * Extract operator names from activity descriptions and enrich records.
     */
    private void enrichWithOperatorNames(List<DeviceActivity> activities) {
        if (activities == null || activities.isEmpty()) {
            return;
        }

        // First try to extract from description
        for (DeviceActivity da : activities) {
            String description = da.getDescription();
            if (description != null) {
                Matcher operatorMatcher = OPERATOR_PATTERN.matcher(description);
                if (operatorMatcher.find()) {
                    da.setOperatorName(operatorMatcher.group(1));
                    continue;
                }
                Matcher merchantMatcher = MERCHANT_PATTERN.matcher(description);
                if (merchantMatcher.find()) {
                    da.setOperatorName(merchantMatcher.group(1));
                }
            }
        }

        // For any remaining without names, look up from operators table
        List<Long> operatorIdsToLookup = activities.stream()
                .filter(da -> da.getOperatorName() == null && da.getActionableId() != null)
                .map(DeviceActivity::getActionableId).distinct().toList();

        if (!operatorIdsToLookup.isEmpty()) {
            Map<Long, String> operatorNames = lookupOperatorNames(operatorIdsToLookup);
            for (DeviceActivity da : activities) {
                if (da.getOperatorName() == null && da.getActionableId() != null) {
                    da.setOperatorName(operatorNames.getOrDefault(da.getActionableId(), "Operator"));
                }
            }
        }
    }

    /**
     * Look up operator names from the replicated operators table.
     */
    @SuppressWarnings("unchecked")
    private Map<Long, String> lookupOperatorNames(List<Long> operatorIds) {
        Map<Long, String> result = new LinkedHashMap<>();
        if (operatorIds == null || operatorIds.isEmpty()) {
            return result;
        }

        try {
            String sql = "SELECT o.id, COALESCE(o.name, o.username) FROM operators o WHERE o.id IN :ids";
            List<Object[]> rows = entityManager.createNativeQuery(sql).setParameter("ids", operatorIds).getResultList();
            for (Object[] row : rows) {
                Long id = row[0] != null ? ((Number) row[0]).longValue() : null;
                String name = row[1] != null ? row[1].toString() : null;
                if (id != null && name != null) {
                    result.put(id, name);
                }
            }
        } catch (Exception e) {
            // operators table may not be replicated yet
        }

        return result;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Long parseLongOrNull(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

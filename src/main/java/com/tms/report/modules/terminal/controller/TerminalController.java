package com.tms.report.modules.terminal.controller;

import com.tms.report.core.dto.ApiResponse;
import com.tms.report.core.dto.PagedResponse;
import com.tms.report.core.export.XlsxExporter;
import com.tms.report.modules.activity.annotation.LogActivity;
import com.tms.report.modules.grpc.service.ConfigHttpClient;
import com.tms.report.modules.grpc.service.GrpcClient;
import com.tms.report.modules.terminal.model.ProviderKeyStatus;
import com.tms.report.modules.terminal.model.Terminal;
import com.tms.report.modules.terminal.model.TerminalMetric;
import com.tms.report.modules.terminal.repository.ProviderKeyStatusRepository;
import com.tms.report.modules.terminal.repository.TerminalMetricRepository;
import com.tms.report.modules.terminal.repository.TerminalRepository;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/terminals")
@RequiredArgsConstructor
public class TerminalController {

    private final TerminalRepository terminalRepository;
    private final ConfigHttpClient configHttpClient;
    private final GrpcClient grpcClient;
    private final ProviderKeyStatusRepository providerKeyStatusRepository;
    private final TerminalMetricRepository terminalMetricRepository;
    private final EntityManager entityManager;

    @GetMapping
    @PreAuthorize("hasAuthority('manage_terminal')")
    public Map<String, Object> index(@RequestParam Map<String, String> params) {
        int page = Integer.parseInt(params.getOrDefault("page", "1")) - 1;
        int limit = Integer.parseInt(params.getOrDefault("limit", "15"));

        Map<String, Object> extra = new LinkedHashMap<>();
        try {
            Map<String, Object> filters = new LinkedHashMap<>();
            filters.put("make", terminalRepository.findDistinctMakes());
            filters.put("model", terminalRepository.findDistinctModels());
            filters.put("os", terminalRepository.findDistinctOs());
            extra.put("filters", filters);
        } catch (Exception e) {
            extra.put("filters", Map.of());
        }

        String searchPattern = trimToNull(params.get("search"));
        if (searchPattern != null) {
            searchPattern = "%" + searchPattern.toLowerCase() + "%";
        }
        String make = trimToNull(params.get("make"));
        String os = trimToNull(params.get("os"));
        String networkType = trimToNull(params.get("network_type"));
        Integer batteryBelow = parseIntOrNull(params.get("battery_below"));
        Integer printerStatus = parseIntOrNull(params.get("printer_status"));
        LocalDateTime staleSince = parseStaleCutoff(params.get("stale"));
        String mapped = parseMapped(params.get("mapped"));

        // Sort is baked into the native query (ORDER BY t.created_at DESC),
        // so we don't pass a Sort here — Spring would append it post-WHERE
        // using the entity property name `createdAt`, which doesn't exist
        // in the native column space.
        var pageable = PageRequest.of(page, limit);
        var result = terminalRepository.findFiltered(searchPattern, make, os, networkType, batteryBelow, printerStatus,
                staleSince, mapped, parseLocked(params.get("status")), pageable);
        attachMappedUsers(result.getContent());
        return PagedResponse.from(result, "/terminals", extra);
    }

    @GetMapping("/download-sample")
    public void downloadSample(HttpServletResponse response) throws Exception {
        var stream = getClass().getClassLoader().getResourceAsStream("sample/pos_terminals.xlsx");
        if (stream == null) {
            response.sendError(404, "Sample file not found");
            return;
        }
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=terminal_sample_document.xlsx");
        stream.transferTo(response.getOutputStream());
    }

    @GetMapping("/download")
    public void download(@RequestParam Map<String, String> params, HttpServletResponse response) throws Exception {
        String s = trimToNull(params.get("search"));
        final String searchPattern = s != null ? "%" + s.toLowerCase() + "%" : null;
        final String make = trimToNull(params.get("make"));
        final String os = trimToNull(params.get("os"));
        final String networkType = trimToNull(params.get("network_type"));
        final Integer batteryBelow = parseIntOrNull(params.get("battery_below"));
        final Integer printerStatus = parseIntOrNull(params.get("printer_status"));
        final LocalDateTime staleSince = parseStaleCutoff(params.get("stale"));
        final String mapped = parseMapped(params.get("mapped"));
        final String locked = parseLocked(params.get("status"));

        XlsxExporter.streamPaged(response, "terminals",
                new String[]{"ID", "Serial", "OS", "Model", "Make", "User ID", "Agent", "Active", "Created At"}, 1000,
                (page, size) -> {
                    var content = terminalRepository.findFiltered(searchPattern, make, os, networkType, batteryBelow,
                            printerStatus, staleSince, mapped, locked, PageRequest.of(page, size)).getContent();
                    attachMappedUsers(content);
                    return content;
                },
                row -> new String[]{String.valueOf(row.getId()), row.getSerial(), row.getOs(), row.getModel(),
                        row.getMake(), row.getUserId() != null ? row.getUserId().toString() : "",
                        row.getUser() != null ? row.getUser().getName() : "",
                        row.getActive() != null ? row.getActive().toString() : "",
                        row.getCreatedAt() != null ? row.getCreatedAt().toString() : ""});
    }

    @GetMapping("/{id}")
    public ApiResponse<Terminal> show(@PathVariable Long id) {
        Terminal terminal = terminalRepository.findById(id).orElseThrow();
        attachMappedUsers(List.of(terminal));
        return ApiResponse.success(terminal);
    }

    @LogActivity(action = "create", description = "{admin} uploaded terminals file")
    @PostMapping(consumes = "multipart/form-data")
    @PreAuthorize("hasAuthority('manage_terminal')")
    public ApiResponse<Map<String, Object>> store(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw new RuntimeException("File is required");
        }
        String filename = file.getOriginalFilename();
        if (filename == null
                || (!filename.endsWith(".xlsx") && !filename.endsWith(".xls") && !filename.endsWith(".csv"))) {
            throw new RuntimeException("File must be xlsx, xls, or csv");
        }
        var result = configHttpClient.uploadTerminals(file);
        return ApiResponse.success((Map<String, Object>) result.get("data"));
    }

    @GetMapping("/all-with-details")
    public ApiResponse<List<Terminal>> allWithDetails() {
        return ApiResponse.success(terminalRepository.findAll());
    }

    @LogActivity(action = "unmap", description = "{admin} unmapped the terminal of {user}", userFrom = "entity:Terminal")
    @PatchMapping("/{id}/unmap")
    public ApiResponse<Map<String, Object>> unmap(@PathVariable Long id) {
        return ApiResponse.success(grpcClient.unmapTerminal(id.toString()));
    }

    // ── Provider Key Health ─────────────────────────────────

    /**
     * GET /terminals/key-health — summary of provider key download status. Shows
     * how many terminals have keys ready, failed, or pending per provider.
     */
    @GetMapping("/key-health")
    public ApiResponse<Map<String, Object>> keyHealth() {
        try {
            Map<String, Object> nibss = new LinkedHashMap<>();
            nibss.put("ready", providerKeyStatusRepository.countReady());
            nibss.put("failed", providerKeyStatusRepository.countFailed());
            nibss.put("pending", providerKeyStatusRepository.countPending());

            List<ProviderKeyStatus> failures = providerKeyStatusRepository.findByKeyStatus("failed");
            nibss.put("failures",
                    failures.stream()
                            .map(f -> Map.of("terminal_id", f.getTerminalId(), "last_error",
                                    f.getLastError() != null ? f.getLastError() : "", "created_at",
                                    f.getCreatedAt() != null ? f.getCreatedAt().toString() : ""))
                            .toList());

            return ApiResponse.success(Map.of("nibss", nibss));
        } catch (Exception e) {
            // nibss_active_terminals table may not exist yet
            return ApiResponse
                    .success(Map.of("nibss", Map.of("ready", 0, "failed", 0, "pending", 0, "failures", List.of())));
        }
    }

    /**
     * GET /terminals/{serial}/key-status — key download status for a specific
     * terminal.
     */
    @GetMapping("/{serial}/key-status")
    public ApiResponse<Map<String, Object>> keyStatus(@PathVariable String serial) {
        try {
            ProviderKeyStatus nibss = providerKeyStatusRepository.findByTerminalId(serial);

            Map<String, Object> result = new LinkedHashMap<>();
            if (nibss != null) {
                result.put("nibss",
                        Map.of("status", nibss.getKeyStatus(), "last_error",
                                nibss.getLastError() != null ? nibss.getLastError() : "", "keys_downloaded_at",
                                nibss.getKeysDownloadedAt() != null ? nibss.getKeysDownloadedAt().toString() : "",
                                "last_used_at", nibss.getLastUsedAt() != null ? nibss.getLastUsedAt().toString() : ""));
            } else {
                result.put("nibss", Map.of("status", "not_prepped"));
            }

            return ApiResponse.success(result);
        } catch (Exception e) {
            return ApiResponse.success(Map.of("nibss", Map.of("status", "not_prepped")));
        }
    }

    // ── Per-POS device virtual accounts ──────────────────────

    /**
     * GET /terminals/{serial}/virtual-accounts — the per-device virtual accounts
     * provisioned for this POS terminal: rows in the (replicated)
     * {@code virtual_accounts} table with {@code purpose='pos-device'} and
     * {@code purpose_reference=<serial>}. One VA per active funding provider (Wema,
     * 9PSB, Globus, …). Soft-disabled VAs ({@code disabled_at} set) are excluded so
     * the UI only shows accounts that currently accept funding.
     */
    @GetMapping("/{serial}/virtual-accounts")
    @PreAuthorize("hasAuthority('manage_terminal')")
    public ApiResponse<List<Map<String, Object>>> virtualAccounts(@PathVariable String serial) {
        try {
            List<Object[]> rows = entityManager
                    .createNativeQuery("SELECT account_name, account_number, bank_name, bank_code, single_use, "
                            + "created_at FROM virtual_accounts "
                            + "WHERE purpose = 'pos-device' AND purpose_reference = :serial AND disabled_at IS NULL "
                            + "ORDER BY bank_name")
                    .setParameter("serial", serial).getResultList();

            List<Map<String, Object>> accounts = rows.stream().map(va -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("account_name", str(va[0]));
                m.put("account_number", str(va[1]));
                m.put("bank_name", str(va[2]));
                m.put("bank_code", str(va[3]));
                m.put("single_use", va[4] instanceof Boolean b ? b : Boolean.parseBoolean(str(va[4])));
                m.put("created_at", str(va[5]));
                return m;
            }).toList();

            return ApiResponse.success(accounts);
        } catch (Exception e) {
            // virtual_accounts may not be replicated yet on a fresh deploy — return
            // an empty list rather than failing the terminal detail page.
            return ApiResponse.success(List.of());
        }
    }

    // ── Device Metrics ───────────────────────────────────────

    /**
     * GET /terminals/{serial}/metrics/latest — most recent hardware/runtime
     * snapshot the device reported. Returns 404 when the device has never checked
     * in.
     */
    @GetMapping("/{serial}/metrics/latest")
    @PreAuthorize("hasAuthority('manage_terminal')")
    public ApiResponse<TerminalMetric> latestMetrics(@PathVariable String serial) {
        return terminalMetricRepository.findFirstBySerialOrderByCreatedAtDesc(serial).map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.error(404, "No metrics recorded for terminal"));
    }

    /**
     * GET /terminals/{serial}/metrics — paged time-series for one device. Optional
     * {@code from} / {@code to} are ISO-8601 date-times.
     */
    @GetMapping("/{serial}/metrics")
    @PreAuthorize("hasAuthority('manage_terminal')")
    public Map<String, Object> metricsHistory(@PathVariable String serial, @RequestParam Map<String, String> params) {
        int page = Integer.parseInt(params.getOrDefault("page", "1")) - 1;
        int limit = Math.min(Integer.parseInt(params.getOrDefault("limit", "100")), 500);

        LocalDateTime from = parseLocalDateTimeOrNull(params.get("from"));
        LocalDateTime to = parseLocalDateTimeOrNull(params.get("to"));

        var pageable = PageRequest.of(page, limit);
        var result = (from != null && to != null)
                ? terminalMetricRepository.findBySerialAndCreatedAtBetweenOrderByCreatedAtDesc(serial, from, to,
                        pageable)
                : terminalMetricRepository.findBySerialOrderByCreatedAtDesc(serial, pageable);

        return PagedResponse.from(result, "/terminals/" + serial + "/metrics");
    }

    /**
     * GET /terminals/metrics/fleet-health — single-shot summary used by the tms-ui
     * dashboard. Counts of devices reporting, low-battery, printer issues, and
     * stale (no check-in in 24h).
     */
    @GetMapping("/metrics/fleet-health")
    @PreAuthorize("hasAuthority('manage_terminal')")
    public ApiResponse<Map<String, Object>> fleetHealth() {
        Map<String, Object> stats = new LinkedHashMap<>();
        try {
            stats.put("reporting", terminalMetricRepository.countReporting());
            stats.put("low_battery", terminalMetricRepository.countLowBattery(20));
            stats.put("printer_not_ready", terminalMetricRepository.countPrinterNotReady());
            stats.put("stale_24h", terminalMetricRepository.countStale(LocalDateTime.now().minusHours(24)));
        } catch (Exception e) {
            // terminal_metrics may not be replicated yet on a fresh deploy
            stats.put("reporting", 0L);
            stats.put("low_battery", 0L);
            stats.put("printer_not_ready", 0L);
            stats.put("stale_24h", 0L);
        }
        return ApiResponse.success(stats);
    }

    private static LocalDateTime parseLocalDateTimeOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            // Accept full ISO timestamps; trim trailing Z if present (DB column is naive).
            String trimmed = value.endsWith("Z") ? value.substring(0, value.length() - 1) : value;
            return LocalDateTime.parse(trimmed);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Normalises the {@code mapped} query param to {@code "true"} / {@code "false"}
     * / {@code null}. Accepts {@code true/false}, {@code yes/no}, {@code 1/0},
     * {@code mapped/unmapped} so the UI filter is forgiving. Anything unrecognised
     * disables the filter.
     */
    private static String parseMapped(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        switch (trimmed.toLowerCase()) {
            case "true", "yes", "1", "mapped" -> {
                return "true";
            }
            case "false", "no", "0", "unmapped" -> {
                return "false";
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * Normalises the {@code status} query param to the {@code locked} filter value
     * {@code "true"} / {@code "false"} / {@code null}. Accepts
     * {@code locked/active} (matching the UI labels) as well as {@code true/false},
     * {@code yes/no}, {@code 1/0}. Anything unrecognised disables the filter.
     */
    private static String parseLocked(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        switch (trimmed.toLowerCase()) {
            case "locked", "true", "yes", "1" -> {
                return "true";
            }
            case "active", "unlocked", "false", "no", "0" -> {
                return "false";
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * Populates the transient {@code user} on each terminal from its
     * {@code user_id}, resolving display name + email in a single batched query so
     * the UI can show which agent a device is mapped to. Unmapped terminals
     * ({@code user_id == null}) are left as-is.
     */
    private void attachMappedUsers(List<Terminal> terminals) {
        if (terminals == null || terminals.isEmpty()) {
            return;
        }
        List<Long> userIds = terminals.stream().map(Terminal::getUserId).filter(java.util.Objects::nonNull).distinct()
                .toList();
        if (userIds.isEmpty()) {
            return;
        }
        Map<Long, Terminal.MappedUser> byId = new LinkedHashMap<>();
        try {
            for (Object[] row : terminalRepository.findUserSummaries(userIds)) {
                Long id = row[0] != null ? ((Number) row[0]).longValue() : null;
                if (id == null) {
                    continue;
                }
                String name = row[1] != null ? row[1].toString() : null;
                String email = row[2] != null ? row[2].toString() : null;
                byId.put(id, new Terminal.MappedUser(id, name, email));
            }
        } catch (Exception e) {
            // users/profiles may not be replicated yet on a fresh deploy — leave
            // terminals unenriched rather than failing the whole listing.
            return;
        }
        for (Terminal t : terminals) {
            if (t.getUserId() != null) {
                t.setUser(byId.get(t.getUserId()));
            }
        }
    }

    private static Integer parseIntOrNull(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String str(Object value) {
        return value != null ? value.toString() : null;
    }

    /**
     * Parses the {@code stale} filter value. Accepts an integer number of hours
     * (e.g. {@code "24"}) and returns "now minus that many hours" — terminals whose
     * latest metric is older than this cutoff (or never reported) are considered
     * stale. The string {@code "true"} defaults to 24 hours.
     */
    private static LocalDateTime parseStaleCutoff(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        int hours;
        if ("true".equalsIgnoreCase(trimmed)) {
            hours = 24;
        } else {
            try {
                hours = Integer.parseInt(trimmed);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return LocalDateTime.now().minusHours(hours);
    }

    // ── Lock / Unlock ─────────────────────────────────────────

    /**
     * POST /terminals/{id}/lock — Admin lock with a reason message. The POS polls
     * config-service's {@code /terminals/serial/{serial}/status} on launch and
     * after every transaction, and renders a block screen with the supplied message
     * until an admin clears the lock.
     */
    @LogActivity(action = "lockTerminal", description = "{admin} locked terminal {body.serial}")
    @PreAuthorize("hasAuthority('manage_terminal')")
    @PostMapping("/{id}/lock")
    public ApiResponse<Map<String, Object>> lockTerminal(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String message = body != null && body.get("message") != null ? body.get("message").toString().trim() : "";
        if (message.isEmpty()) {
            return ApiResponse.error(422, "message is required");
        }
        Map<String, Object> response = configHttpClient.postJson("/terminals/" + id + "/lock",
                Map.of("message", message));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getOrDefault("data", Map.of());
        return ApiResponse.success(data);
    }

    /**
     * POST /terminals/{id}/unlock — Admin clears the lock. The POS resumes normal
     * operation on its next status poll.
     */
    @LogActivity(action = "unlockTerminal", description = "{admin} unlocked terminal {id}")
    @PreAuthorize("hasAuthority('manage_terminal')")
    @PostMapping("/{id}/unlock")
    public ApiResponse<Map<String, Object>> unlockTerminal(@PathVariable Long id) {
        Map<String, Object> response = configHttpClient.postJson("/terminals/" + id + "/unlock", Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getOrDefault("data", Map.of());
        return ApiResponse.success(data);
    }

    /**
     * POST /terminals/{id}/request-prep — Admin remotely triggers a key re-prep on
     * the device. config-service publishes a {@code pos_prep_requested} MQTT
     * envelope to the agent's user topic; the POS app's notification dispatcher
     * invokes {@code TerminalPrepController.forcePrep} on receipt, which downloads
     * a fresh TMK/TPK pair and re-injects them into the secure pin pad. No agent
     * action is required — the swap completes in the background.
     */
    @LogActivity(action = "requestTerminalPrep", description = "{admin} requested re-prep on terminal {id}")
    @PreAuthorize("hasAuthority('manage_terminal')")
    @PostMapping("/{id}/request-prep")
    public ApiResponse<Map<String, Object>> requestTerminalPrep(@PathVariable Long id) {
        Map<String, Object> response = configHttpClient.postJson("/terminals/" + id + "/request-prep", Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getOrDefault("data", Map.of());
        return ApiResponse.success(data);
    }

    // ── Per-POS device VA backfill ───────────────────────────

    /**
     * POST /terminals/backfill-device-accounts — Re-emits a {@code terminal-mapped}
     * event for every terminal currently bound to a user, so
     * virtual-account-service can provision a dedicated VA per active provider for
     * terminals that were mapped <em>before</em> the device-VA feature shipped.
     * Idempotent — safe to run any number of times.
     *
     * <p>
     * Optional body filters:
     * <ul>
     * <li>{@code user_id} — re-emit only for one agent's terminals (surgical
     * recovery).</li>
     * <li>{@code limit} — cap how many rows are processed in one call (page a large
     * fleet).</li>
     * </ul>
     */
    @LogActivity(action = "backfillTerminalMapped", description = "{admin} backfilled per-POS-device virtual accounts")
    @PreAuthorize("hasAuthority('manage_terminal')")
    @PostMapping("/backfill-device-accounts")
    public ApiResponse<Map<String, Object>> backfillDeviceAccounts(
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> payload = body != null ? body : Map.of();
        Map<String, Object> response = configHttpClient.postGrpcCommand("BackfillTerminalMapped", payload);
        return ApiResponse.success(Map.of("message", response.getOrDefault("message", ""), "reference",
                response.getOrDefault("reference", "0")));
    }
}

package com.tms.report.modules.terminal.controller;

import com.tms.report.core.dto.ApiResponse;
import com.tms.report.core.dto.PagedResponse;
import com.tms.report.core.export.XlsxExporter;
import com.tms.report.core.filter.QueryFilterHelper;
import com.tms.report.core.security.MerchantScope;
import com.tms.report.modules.grpc.service.ConfigHttpClient;
import com.tms.report.modules.grpc.service.GrpcClient;
import com.tms.report.modules.terminal.model.ProviderKeyStatus;
import com.tms.report.modules.terminal.model.Terminal;
import com.tms.report.modules.terminal.model.TerminalMetric;
import com.tms.report.modules.terminal.repository.ProviderKeyStatusRepository;
import com.tms.report.modules.terminal.repository.TerminalMetricRepository;
import com.tms.report.modules.terminal.repository.TerminalRepository;
import com.tms.report.modules.transaction.service.TransactionService;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.HttpServletRequest;
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
    private final MerchantScope merchantScope;
    private final TransactionService transactionService;

    /**
     * Merchant id for the repository query. Fails closed to a non-matching sentinel
     * ({@code -1}) when there is no merchant in context, so the estate is never
     * leaked. The endpoints require authentication, so this is normally set.
     */
    private Long merchantScopeId() {
        Long m = merchantScope.merchantId();
        return m != null ? m : -1L;
    }

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
                staleSince, mapped, parseLocked(params.get("status")), merchantScopeId(), merchantScope.terminalId(),
                pageable);
        attachMappedUsers(result.getContent());
        return PagedResponse.from(result, "/terminals", extra);
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
        final Long merchantId = merchantScopeId();
        final Long terminalId = merchantScope.terminalId();

        XlsxExporter.streamPaged(response, "terminals",
                new String[]{"ID", "Serial", "OS", "Model", "Make", "User ID", "Agent", "Active", "Created At"}, 1000,
                (page, size) -> {
                    var content = terminalRepository
                            .findFiltered(searchPattern, make, os, networkType, batteryBelow, printerStatus, staleSince,
                                    mapped, locked, merchantId, terminalId, PageRequest.of(page, size))
                            .getContent();
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
        Terminal terminal = loadScopedTerminal(id);
        attachMappedUsers(List.of(terminal));
        return ApiResponse.success(terminal);
    }

    /**
     * Loads a terminal by id and enforces merchant scope: the terminal must belong
     * to the authenticated merchant ({@code terminals.user_id = merchant_id}), and
     * for a cashier locked to one terminal, it must be that terminal. Throws
     * {@link java.util.NoSuchElementException} (rendered 404) otherwise, so a
     * merchant can't probe another merchant's estate by id.
     */
    private Terminal loadScopedTerminal(Long id) {
        Terminal terminal = terminalRepository.findById(id)
                .orElseThrow(() -> new java.util.NoSuchElementException("Terminal not found"));
        Long merchantId = merchantScope.merchantId();
        boolean ok = merchantId != null && merchantId.equals(terminal.getUserId());
        Long terminalScope = merchantScope.terminalId();
        if (ok && terminalScope != null) {
            ok = terminalScope.equals(terminal.getId());
        }
        if (!ok) {
            throw new java.util.NoSuchElementException("Terminal not found");
        }
        return terminal;
    }

    /**
     * GET /terminals/{id}/transactions — all transactions done on this terminal.
     * Powers the "Transactions" tab on the terminal-detail page. Matches on the
     * device serial (transactions.terminal_id / metadata serial) and is further
     * constrained by tenant scope inside {@link TransactionService}, so a bank only
     * ever sees its own merchants' activity on the device.
     */
    @GetMapping("/{id}/transactions")
    public Map<String, Object> transactions(@PathVariable Long id, @RequestParam Map<String, String> params,
            HttpServletRequest request) {
        Terminal terminal = loadScopedTerminal(id);
        applyTerminalScope(params, terminal);
        extractDates(request, params);
        return PagedResponse.from(transactionService.index(params), "/terminals/" + id + "/transactions",
                Map.of("filters", transactionService.filters(), "stats", transactionService.getSummary(params)));
    }

    /**
     * GET /terminals/{id}/transactions/download — XLSX export of the same
     * terminal-scoped, filtered transaction set shown in the tab.
     */
    @GetMapping("/{id}/transactions/download")
    public void downloadTransactions(@PathVariable Long id, @RequestParam Map<String, String> params,
            HttpServletRequest request, HttpServletResponse response) throws Exception {
        Terminal terminal = loadScopedTerminal(id);
        applyTerminalScope(params, terminal);
        extractDates(request, params);
        XlsxExporter.streamPaged(response, "terminal-transactions",
                new String[]{"Reference", "Amount", "Product", "Provider", "Channel", "Status", "Date"}, 1000,
                (page, size) -> transactionService.index(QueryFilterHelper.pageParams(params, page, size)).getContent(),
                row -> new String[]{row.getReference(), row.getAmount(),
                        row.getProduct() != null ? row.getProduct().getName() : "",
                        row.getProvider() != null ? row.getProvider().getName() : "",
                        row.getChannel() != null ? row.getChannel().getName() : "",
                        row.getStatus() != null ? row.getStatus().getName() : "",
                        row.getCreatedAt() != null ? row.getCreatedAt().toString() : ""});
    }

    /**
     * Stamps the terminal's device serial as the {@code terminal_serial} filter. A
     * terminal with no serial gets a sentinel that matches nothing, so the tab
     * fails closed to an empty list rather than leaking the bank's whole feed.
     */
    private void applyTerminalScope(Map<String, String> params, Terminal terminal) {
        String serial = terminal.getSerial();
        params.put("terminal_serial", serial != null && !serial.isBlank() ? serial : "__no_serial__");
    }

    private void extractDates(HttpServletRequest request, Map<String, String> params) {
        String[] dates = request.getParameterValues("dates[]");
        if (dates != null && dates.length >= 2) {
            params.put("dates[0]", dates[0]);
            params.put("dates[1]", dates[1]);
        }
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

    // ── Remote Prep ────────────────────────────────────────────

    /**
     * POST /terminals/{id}/request-prep — remotely trigger key injection on a
     * terminal. Pushes an MQTT message to the device so it re-downloads TMK/TPK
     * pairs from the provider and re-injects them into the secure PIN pad. Useful
     * after key rotation or when a device is stuck on stale keys.
     */
    @PostMapping("/{id}/request-prep")
    @PreAuthorize("hasAuthority('manage_terminal')")
    public ApiResponse<Map<String, Object>> requestPrep(@PathVariable Long id) {
        Terminal terminal = loadScopedTerminal(id);
        String serial = terminal.getSerial();
        if (serial == null || serial.isBlank()) {
            return ApiResponse.error(400, "Terminal has no serial number");
        }
        try {
            boolean delivered = configHttpClient.requestPrep(serial);
            return ApiResponse.success(Map.of("serial", serial, "delivered", delivered),
                    delivered ? "Prep signal sent to " + serial : "Terminal offline — prep queued");
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to request prep: " + e.getMessage());
        }
    }

    // ── Lock / Unlock ────────────────────────────────────────────

    /**
     * POST /terminals/{id}/lock — lock a terminal with a reason message. The POS
     * polls the per-serial status endpoint and renders a contact-support block
     * screen until the lock is cleared.
     */
    @PostMapping("/{id}/lock")
    @PreAuthorize("hasAuthority('manage_terminal')")
    public ApiResponse<Map<String, Object>> lock(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Terminal terminal = loadScopedTerminal(id);
        String serial = terminal.getSerial();
        if (serial == null || serial.isBlank()) {
            return ApiResponse.error(400, "Terminal has no serial number");
        }
        String message = body.get("message") != null ? body.get("message").toString() : "";
        if (message.isBlank()) {
            return ApiResponse.error(400, "Lock message is required");
        }
        try {
            configHttpClient.postJson("/terminals/" + serial + "/lock", Map.of("message", message));
            return ApiResponse.success(Map.of("serial", serial, "locked", true), "Terminal " + serial + " locked");
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to lock terminal: " + e.getMessage());
        }
    }

    /**
     * POST /terminals/{id}/unlock — clear an existing lock so the device can resume
     * taking transactions.
     */
    @PostMapping("/{id}/unlock")
    @PreAuthorize("hasAuthority('manage_terminal')")
    public ApiResponse<Map<String, Object>> unlock(@PathVariable Long id) {
        Terminal terminal = loadScopedTerminal(id);
        String serial = terminal.getSerial();
        if (serial == null || serial.isBlank()) {
            return ApiResponse.error(400, "Terminal has no serial number");
        }
        try {
            configHttpClient.postJson("/terminals/" + serial + "/unlock", Map.of());
            return ApiResponse.success(Map.of("serial", serial, "locked", false), "Terminal " + serial + " unlocked");
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to unlock terminal: " + e.getMessage());
        }
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
}

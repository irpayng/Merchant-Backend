package com.tms.report.modules.branch.service;

import com.tms.report.core.dto.PagedResponse;
import com.tms.report.core.exception.AppException;
import com.tms.report.modules.branch.dto.BranchCreateRequest;
import com.tms.report.modules.branch.dto.BranchResponse;
import com.tms.report.modules.branch.dto.BranchUpdateRequest;
import com.tms.report.modules.grpc.service.GrpcClient;
import com.tms.report.modules.terminal.model.Terminal;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for merchant branch management.
 *
 * <p>
 * Branches are stored in tms-config (config database). All branch read/write
 * operations go through gRPC to tms-config as the single source of truth. Local
 * database queries are used only for enrichment data (terminal counts,
 * transaction volumes) that live in the report database.
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BranchService {

    private final EntityManager entityManager;
    private final GrpcClient grpcClient;

    /**
     * List branches for a merchant with pagination and filtering. Fetches branch
     * data from tms-config via gRPC, enriches with terminal counts and transaction
     * volumes from local database.
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Page<Map<String, Object>> listBranches(Long merchantId, Map<String, String> params) {
        int page = Integer.parseInt(params.getOrDefault("page", "1")) - 1;
        int limit = Integer.parseInt(params.getOrDefault("limit", "15"));

        String search = params.get("search");
        String status = params.get("status");
        boolean activeOnly = "active".equalsIgnoreCase(status);

        // Fetch all branches from tms-config via gRPC (source of truth)
        List<Map<String, Object>> allBranches = grpcClient.listMerchantBranches(merchantId, activeOnly);

        // Apply search filter locally if provided
        if (search != null && !search.isBlank()) {
            String lowerSearch = search.toLowerCase();
            allBranches = allBranches.stream().filter(b -> {
                String name = (String) b.get("name");
                String address = (String) b.get("address");
                String code = (String) b.get("code");
                return (name != null && name.toLowerCase().contains(lowerSearch))
                        || (address != null && address.toLowerCase().contains(lowerSearch))
                        || (code != null && code.toLowerCase().contains(lowerSearch));
            }).toList();
        }

        // Apply status filter locally if not activeOnly (which is handled by gRPC)
        if (status != null && !status.isBlank() && !activeOnly) {
            String lowerStatus = status.toLowerCase();
            allBranches = allBranches.stream().filter(b -> lowerStatus.equals(b.get("status"))).toList();
        }

        // Sort: primary branches first, then by name
        allBranches = allBranches.stream().sorted((a, b) -> {
            boolean aPrimary = Boolean.TRUE.equals(a.get("is_primary"));
            boolean bPrimary = Boolean.TRUE.equals(b.get("is_primary"));
            if (aPrimary != bPrimary) {
                return aPrimary ? -1 : 1;
            }
            String aName = (String) a.get("name");
            String bName = (String) b.get("name");
            return (aName != null ? aName : "").compareTo(bName != null ? bName : "");
        }).toList();

        long total = allBranches.size();

        // Apply pagination
        int start = page * limit;
        int end = Math.min(start + limit, allBranches.size());
        List<Map<String, Object>> pagedBranches = start < allBranches.size()
                ? allBranches.subList(start, end)
                : List.of();

        // Enrich with terminal counts and volumes from local database
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> branch : pagedBranches) {
            Long branchId = ((Number) branch.get("id")).longValue();
            Map<String, Object> enrichment = getEnrichmentData(branchId);

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", branchId);
            m.put("branch_id", String.valueOf(branchId));
            m.put("name", branch.get("name"));
            m.put("code", branch.get("code"));
            m.put("address", branch.get("address"));
            m.put("location", branch.get("address")); // alias for frontend compatibility
            m.put("state_code", branch.get("state_code"));
            m.put("lga_code", branch.get("lga_code"));
            m.put("phone_number", branch.get("phone_number"));
            m.put("email", branch.get("email"));
            m.put("status", formatStatus((String) branch.get("status")));
            m.put("is_primary", branch.get("is_primary"));
            m.put("created_at", branch.get("created_at"));
            m.put("updated_at", branch.get("updated_at"));
            m.put("terminals", enrichment.get("terminal_count"));
            m.put("total_volume", enrichment.get("total_volume"));
            out.add(m);
        }

        return new PageImpl<>(out, PageRequest.of(page, limit), total);
    }

    /**
     * Get enrichment data (terminal count and volume) for a branch from local DB.
     */
    private Map<String, Object> getEnrichmentData(Long branchId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("terminal_count", 0L);
        result.put("total_volume", 0L);

        try {
            // Terminal count
            Long terminalCount = ((Number) entityManager
                    .createNativeQuery("SELECT COUNT(*) FROM terminals WHERE branch_id = :branchId")
                    .setParameter("branchId", branchId).getSingleResult()).longValue();
            result.put("terminal_count", terminalCount);

            // Total volume from successful transactions via terminals
            Object volumeResult = entityManager.createNativeQuery("""
                    SELECT COALESCE(SUM(tx.amount), 0)
                    FROM terminals t
                    JOIN transactions tx ON tx.terminal_id = t.serial
                    WHERE t.branch_id = :branchId AND tx.status_code = 'successful'
                    """).setParameter("branchId", branchId).getSingleResult();
            result.put("total_volume", volumeResult != null ? ((Number) volumeResult).longValue() : 0L);
        } catch (Exception e) {
            log.warn("Failed to get enrichment data for branch {}: {}", branchId, e.getMessage());
        }

        return result;
    }

    /**
     * Get a single branch by ID. Fetches branch data from tms-config via gRPC,
     * enriches with terminal counts and transaction volumes from local database.
     */
    @Transactional(readOnly = true)
    public BranchResponse getBranch(Long branchId, Long merchantId) {
        // Fetch branch from tms-config via gRPC (source of truth)
        Map<String, Object> branch = grpcClient.getMerchantBranch(branchId, merchantId);
        if (branch == null) {
            return null;
        }

        // Get enrichment data from local database
        Map<String, Object> enrichment = getEnrichmentData(branchId);
        Long terminalCount = (Long) enrichment.get("terminal_count");
        Long totalVolume = (Long) enrichment.get("total_volume");

        // Get settlement account ID from tms-config (stored on branch)
        Long settlementAccountId = branch.get("settlement_account_id") != null
                ? ((Number) branch.get("settlement_account_id")).longValue()
                : null;

        // Fetch settlement account details via gRPC if account is assigned
        String accountNumber = null;
        String accountName = null;
        String bankCode = null;
        String bankName = null;

        if (settlementAccountId != null && settlementAccountId > 0) {
            List<Map<String, Object>> accounts = grpcClient.listMerchantSettlementAccounts(merchantId);
            for (Map<String, Object> acct : accounts) {
                if (settlementAccountId.equals(((Number) acct.get("id")).longValue())) {
                    accountNumber = (String) acct.get("account_number");
                    accountName = (String) acct.get("account_name");
                    bankCode = (String) acct.get("bank_code");
                    // bank_name not returned by gRPC, leave as null
                    break;
                }
            }
        }

        Long id = ((Number) branch.get("id")).longValue();
        String createdAt = (String) branch.get("created_at");
        String updatedAt = (String) branch.get("updated_at");

        return BranchResponse.builder().id(id).branchId(String.valueOf(id)).name((String) branch.get("name"))
                .code((String) branch.get("code")).address((String) branch.get("address"))
                .location((String) branch.get("address")).stateCode((String) branch.get("state_code"))
                .lgaCode((String) branch.get("lga_code")).phoneNumber((String) branch.get("phone_number"))
                .email((String) branch.get("email")).status(formatStatus((String) branch.get("status")))
                .isPrimary(Boolean.TRUE.equals(branch.get("is_primary")))
                .createdAt(createdAt != null ? parseInstant(createdAt) : null)
                .updatedAt(updatedAt != null ? parseInstant(updatedAt) : null)
                .terminals(terminalCount != null ? terminalCount.intValue() : 0)
                .totalVolume(totalVolume != null ? totalVolume : 0L)
                .settlementAccountId(
                        settlementAccountId != null && settlementAccountId > 0 ? settlementAccountId : null)
                .settlementAccountNumber(accountNumber).settlementAccountName(accountName).settlementBankCode(bankCode)
                .settlementBankName(bankName).build();
    }

    /**
     * Parse a timestamp string to Instant.
     */
    private Instant parseInstant(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(timestamp);
        } catch (Exception e) {
            // Try parsing as LocalDateTime and convert
            try {
                return java.time.LocalDateTime.parse(timestamp).atZone(java.time.ZoneId.systemDefault()).toInstant();
            } catch (Exception ex) {
                log.warn("Failed to parse timestamp: {}", timestamp);
                return null;
            }
        }
    }

    /**
     * Create a new branch via gRPC to tms-config (single source of truth).
     */
    @Transactional
    public BranchResponse createBranch(Long merchantId, BranchCreateRequest request) {
        // Build request data for gRPC - tms-config generates code if not provided
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", merchantId);
        data.put("name", request.getName());
        // Only send code if explicitly provided by user
        if (request.getCode() != null && !request.getCode().isBlank()) {
            data.put("code", request.getCode());
        }
        if (request.getAddress() != null) {
            data.put("address", request.getAddress());
        } else if (request.getLocation() != null) {
            data.put("address", request.getLocation());
        }
        if (request.getStateCode() != null) {
            data.put("state_code", request.getStateCode());
        }
        if (request.getLgaCode() != null) {
            data.put("lga_code", request.getLgaCode());
        }
        if (request.getPhoneNumber() != null) {
            data.put("phone_number", request.getPhoneNumber());
        }
        if (request.getEmail() != null) {
            data.put("email", request.getEmail());
        }
        if (Boolean.TRUE.equals(request.getIsPrimary())) {
            data.put("is_primary", true);
        }

        // Create branch via gRPC (tms-config handles primary flag and ID generation)
        var result = grpcClient.createMerchantBranch(data);
        if (!Boolean.TRUE.equals(result.get("success"))) {
            String msg = result.get("message") != null ? result.get("message").toString() : "Unknown error";
            throw new AppException("Failed to create branch: " + msg, HttpStatus.INTERNAL_SERVER_ERROR);
        }

        // Extract branch ID from response
        String dataJson = (String) result.get("data");
        Long branchId = null;
        if (dataJson != null && !dataJson.isBlank()) {
            try {
                var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
                var node = objectMapper.readTree(dataJson);
                if (node.has("id")) {
                    branchId = node.get("id").asLong();
                }
            } catch (Exception e) {
                log.warn("Failed to parse branch ID from response: {}", e.getMessage());
            }
        }

        if (branchId == null) {
            throw new AppException("Branch created but ID not returned", HttpStatus.INTERNAL_SERVER_ERROR);
        }

        log.info("Created branch {} for merchant {} via gRPC", branchId, merchantId);
        return getBranch(branchId, merchantId);
    }

    /**
     * Update an existing branch via gRPC to tms-config (single source of truth).
     */
    @Transactional
    public BranchResponse updateBranch(Long branchId, Long merchantId, BranchUpdateRequest request) {
        // Build request data with only non-null fields
        Map<String, Object> data = new LinkedHashMap<>();
        if (request.getName() != null) {
            data.put("name", request.getName());
        }
        if (request.getAddress() != null) {
            data.put("address", request.getAddress());
        } else if (request.getLocation() != null) {
            data.put("address", request.getLocation());
        }
        if (request.getStateCode() != null) {
            data.put("state_code", request.getStateCode());
        }
        if (request.getLgaCode() != null) {
            data.put("lga_code", request.getLgaCode());
        }
        if (request.getPhoneNumber() != null) {
            data.put("phone_number", request.getPhoneNumber());
        }
        if (request.getEmail() != null) {
            data.put("email", request.getEmail());
        }
        if (request.getStatus() != null) {
            data.put("status", request.getStatus().toLowerCase());
        }

        // Update branch via gRPC
        var result = grpcClient.updateMerchantBranch(branchId, merchantId, data);
        if (!Boolean.TRUE.equals(result.get("success"))) {
            // Branch not found or update failed
            return null;
        }

        log.info("Updated branch {} for merchant {} via gRPC", branchId, merchantId);
        return getBranch(branchId, merchantId);
    }

    /**
     * Set a branch as primary via gRPC to tms-config (single source of truth).
     */
    @Transactional
    public BranchResponse setPrimary(Long branchId, Long merchantId) {
        // Set primary via gRPC (tms-config handles clearing existing primary)
        var result = grpcClient.setPrimaryMerchantBranch(branchId, merchantId);
        if (!Boolean.TRUE.equals(result.get("success"))) {
            // Branch not found or operation failed
            return null;
        }

        log.info("Set branch {} as primary for merchant {} via gRPC", branchId, merchantId);
        return getBranch(branchId, merchantId);
    }

    /**
     * Delete a branch via gRPC to tms-config (single source of truth). Pre-flight
     * checks (terminal/TID assignments) are done locally.
     */
    @Transactional
    public boolean deleteBranch(Long branchId, Long merchantId) {
        // Check if branch has POS terminals assigned (local pre-flight check)
        Long terminalCount = ((Number) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM terminals WHERE branch_id = :branchId")
                .setParameter("branchId", branchId).getSingleResult()).longValue();

        if (terminalCount > 0) {
            log.warn("Cannot delete branch {} - has {} terminals assigned", branchId, terminalCount);
            return false;
        }

        // Also check if branch has TIDs assigned (local pre-flight check)
        Long tidCount = ((Number) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM branch_tids WHERE branch_id = :branchId")
                .setParameter("branchId", branchId).getSingleResult()).longValue();

        if (tidCount > 0) {
            log.warn("Cannot delete branch {} - has {} TIDs assigned", branchId, tidCount);
            return false;
        }

        // Delete branch via gRPC
        var result = grpcClient.deleteMerchantBranch(branchId, merchantId);
        boolean success = Boolean.TRUE.equals(result.get("success"));

        if (success) {
            log.info("Deleted branch {} for merchant {} via gRPC", branchId, merchantId);
        }
        return success;
    }

    /**
     * Get branch statistics.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getBranchStats(Long branchId, Long merchantId) {
        // Verify branch exists
        Long count = ((Number) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM merchant_branches WHERE id = :id AND user_id = :merchantId")
                .setParameter("id", branchId).setParameter("merchantId", merchantId).getSingleResult()).longValue();

        if (count == 0) {
            return null;
        }

        Map<String, Object> stats = new LinkedHashMap<>();

        // POS terminal count (from terminals table)
        Long terminals = ((Number) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM terminals WHERE branch_id = :branchId")
                .setParameter("branchId", branchId).getSingleResult()).longValue();
        stats.put("terminals", terminals);

        // Transaction stats (30 days) - prefer branch_id column, fall back to terminal
        // join
        try {
            Object[] txStats = (Object[]) entityManager.createNativeQuery("""
                    SELECT COUNT(*), COALESCE(SUM(tx.amount), 0)
                    FROM transactions tx
                    WHERE (
                        tx.branch_id = :branchId
                        OR (tx.branch_id IS NULL AND EXISTS (
                            SELECT 1 FROM terminals t
                            WHERE t.serial = tx.terminal_id AND t.branch_id = :branchId
                        ))
                    )
                      AND tx.created_at >= NOW() - INTERVAL '30 days'
                      AND tx.status_code = 'completed'
                    """).setParameter("branchId", branchId).getSingleResult();

            stats.put("transactions_30d", num(txStats[0]));
            stats.put("volume_30d", num(txStats[1]));
        } catch (Exception e) {
            stats.put("transactions_30d", 0L);
            stats.put("volume_30d", 0L);
        }

        return stats;
    }

    /**
     * Assign a settlement account to a branch. The account must exist in the
     * merchant's settlement account pool (validated via gRPC to tms-config).
     */
    @Transactional
    public BranchResponse assignSettlementAccount(Long branchId, Long merchantId, Long settlementAccountId) {
        // Update branch settlement account via gRPC to tms-config (source of truth)
        Map<String, Object> data = new HashMap<>();
        data.put("settlement_account_id", settlementAccountId);

        Map<String, Object> result = grpcClient.updateMerchantBranch(branchId, merchantId, data);
        if (!Boolean.TRUE.equals(result.get("success"))) {
            log.warn("Failed to assign settlement account {} to branch {} for merchant {}: {}", settlementAccountId,
                    branchId, merchantId, result.get("message"));
            return null;
        }

        log.info("Assigned settlement account {} to branch {} for merchant {}", settlementAccountId, branchId,
                merchantId);
        return getBranch(branchId, merchantId);
    }

    /**
     * Clear branch settlement account (fall back to merchant default).
     */
    @Transactional
    public BranchResponse clearSettlementAccount(Long branchId, Long merchantId) {
        // Clear branch settlement account via gRPC to tms-config (source of truth)
        Map<String, Object> data = new HashMap<>();
        data.put("clear_settlement_account", true);

        Map<String, Object> result = grpcClient.updateMerchantBranch(branchId, merchantId, data);
        if (!Boolean.TRUE.equals(result.get("success"))) {
            log.warn("Failed to clear settlement account for branch {} of merchant {}: {}", branchId, merchantId,
                    result.get("message"));
            return null;
        }

        log.info("Cleared settlement account for branch {} of merchant {}", branchId, merchantId);
        return getBranch(branchId, merchantId);
    }

    // ────────────────────────────────────────────────────────────── Terminal
    // Assignment

    /**
     * List terminals assigned to a branch.
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Map<String, Object> listBranchTerminals(Long branchId, Long merchantId, Map<String, String> params) {
        // Verify branch exists and belongs to merchant
        Long branchCount = ((Number) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM merchant_branches WHERE id = :id AND user_id = :merchantId")
                .setParameter("id", branchId).setParameter("merchantId", merchantId).getSingleResult()).longValue();

        if (branchCount == 0) {
            return PagedResponse.empty("/branches/" + branchId + "/terminals");
        }

        int page = Integer.parseInt(params.getOrDefault("page", "1")) - 1;
        int limit = Integer.parseInt(params.getOrDefault("limit", "15"));

        StringBuilder where = new StringBuilder("WHERE t.branch_id = :branchId AND t.user_id = :merchantId");
        Map<String, Object> qp = new HashMap<>();
        qp.put("branchId", branchId);
        qp.put("merchantId", merchantId);

        String search = params.get("search");
        if (search != null && !search.isBlank()) {
            where.append(" AND (LOWER(t.serial) LIKE :search OR LOWER(t.model) LIKE :search)");
            qp.put("search", "%" + search.toLowerCase() + "%");
        }

        // Count query
        String countSql = "SELECT COUNT(*) FROM terminals t " + where;
        Query countQ = entityManager.createNativeQuery(countSql);
        qp.forEach(countQ::setParameter);
        long total = ((Number) countQ.getSingleResult()).longValue();

        // Data query with latest metrics and TID from the same branch
        String dataSql = """
                SELECT t.id, t.serial, t.make, t.model, t.os, t.active, t.locked,
                       t.created_at, t.updated_at,
                       m.battery_pct, m.network_type, m.printer_status, m.created_at as last_seen,
                       COALESCE(tid.merchant_physical_addr, '') as location,
                       tid.terminal_id as tid_value
                FROM terminals t
                LEFT JOIN LATERAL (
                    SELECT battery_pct, network_type, printer_status, created_at
                    FROM terminal_metrics
                    WHERE serial = t.serial
                    ORDER BY created_at DESC
                    LIMIT 1
                ) m ON true
                LEFT JOIN LATERAL (
                    SELECT terminal_id, merchant_physical_addr
                    FROM tids
                    WHERE branch_id = t.branch_id
                    ORDER BY created_at DESC
                    LIMIT 1
                ) tid ON true
                """ + where + " ORDER BY t.created_at DESC";

        Query dataQ = entityManager.createNativeQuery(dataSql);
        qp.forEach(dataQ::setParameter);
        dataQ.setFirstResult(page * limit);
        dataQ.setMaxResults(limit);

        List<Object[]> rows = dataQ.getResultList();
        List<Map<String, Object>> out = new ArrayList<>();

        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", num(r[0]));
            m.put("terminal_id", str(r[14]) != null ? str(r[14]) : str(r[1])); // TID from tids table, fallback to
                                                                               // serial
            m.put("serial", str(r[1]));
            m.put("make", str(r[2]));
            m.put("model", str(r[3]));
            m.put("os", str(r[4]));
            m.put("model_os", str(r[3]) + ", " + str(r[4]));
            m.put("active", r[5]);
            m.put("locked", r[6]);
            m.put("status", Boolean.TRUE.equals(r[6]) ? "Locked" : "Active");
            m.put("created_at", timestamp(r[7]));
            m.put("updated_at", timestamp(r[8]));
            m.put("battery_pct", r[9]);
            m.put("network_type", str(r[10]));
            m.put("printer_status", r[11]);
            m.put("last_seen", timestamp(r[12]));
            m.put("location", str(r[13]));
            out.add(m);
        }

        Page<Map<String, Object>> pageResult = new PageImpl<>(out, PageRequest.of(page, limit), total);
        return PagedResponse.from(pageResult, "/branches/" + branchId + "/terminals");
    }

    /**
     * Get terminal stats for a branch (for the stats cards).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getBranchTerminalStats(Long branchId, Long merchantId) {
        // Verify branch exists
        Long branchCount = ((Number) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM merchant_branches WHERE id = :id AND user_id = :merchantId")
                .setParameter("id", branchId).setParameter("merchantId", merchantId).getSingleResult()).longValue();

        if (branchCount == 0) {
            return null;
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        LocalDateTime staleCutoff = LocalDateTime.now().minusHours(24);

        try {
            // Reporting (has metrics within 24h)
            Long reporting = ((Number) entityManager.createNativeQuery("""
                    SELECT COUNT(DISTINCT t.id) FROM terminals t
                    JOIN terminal_metrics m ON m.serial = t.serial
                    WHERE t.branch_id = :branchId AND t.user_id = :merchantId
                      AND m.created_at >= :staleCutoff
                    """).setParameter("branchId", branchId).setParameter("merchantId", merchantId)
                    .setParameter("staleCutoff", staleCutoff).getSingleResult()).longValue();
            stats.put("reporting", reporting);

            // Low battery
            Long lowBattery = ((Number) entityManager
                    .createNativeQuery(
                            """
                                    SELECT COUNT(DISTINCT t.id) FROM terminals t
                                    JOIN LATERAL (
                                        SELECT battery_pct FROM terminal_metrics WHERE serial = t.serial ORDER BY created_at DESC LIMIT 1
                                    ) m ON true
                                    WHERE t.branch_id = :branchId AND t.user_id = :merchantId AND m.battery_pct < 20
                                    """)
                    .setParameter("branchId", branchId).setParameter("merchantId", merchantId).getSingleResult())
                    .longValue();
            stats.put("low_battery", lowBattery);

            // Printer not ready
            Long printerNotReady = ((Number) entityManager
                    .createNativeQuery(
                            """
                                    SELECT COUNT(DISTINCT t.id) FROM terminals t
                                    JOIN LATERAL (
                                        SELECT printer_status FROM terminal_metrics WHERE serial = t.serial ORDER BY created_at DESC LIMIT 1
                                    ) m ON true
                                    WHERE t.branch_id = :branchId AND t.user_id = :merchantId AND m.printer_status != 0
                                    """)
                    .setParameter("branchId", branchId).setParameter("merchantId", merchantId).getSingleResult())
                    .longValue();
            stats.put("printer_not_ready", printerNotReady);

            // Stale (no metrics in 24h)
            Long stale = ((Number) entityManager.createNativeQuery("""
                    SELECT COUNT(*) FROM terminals t
                    WHERE t.branch_id = :branchId AND t.user_id = :merchantId
                      AND NOT EXISTS (
                          SELECT 1 FROM terminal_metrics m WHERE m.serial = t.serial AND m.created_at >= :staleCutoff
                      )
                    """).setParameter("branchId", branchId).setParameter("merchantId", merchantId)
                    .setParameter("staleCutoff", staleCutoff).getSingleResult()).longValue();
            stats.put("stale_24h", stale);

        } catch (Exception e) {
            stats.put("reporting", 0L);
            stats.put("low_battery", 0L);
            stats.put("printer_not_ready", 0L);
            stats.put("stale_24h", 0L);
        }

        return stats;
    }

    /**
     * Assign a terminal to a branch. Multiple branches can share the same terminal,
     * and terminal branch assignment is editable (can reassign to a different
     * branch).
     */
    @Transactional
    public Terminal assignTerminalToBranch(Long branchId, Long terminalId, Long merchantId) {
        // Verify branch exists and belongs to merchant
        Long branchCount = ((Number) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM merchant_branches WHERE id = :id AND user_id = :merchantId")
                .setParameter("id", branchId).setParameter("merchantId", merchantId).getSingleResult()).longValue();

        if (branchCount == 0) {
            return null;
        }

        // Verify terminal exists and belongs to merchant
        Object[] terminalData = (Object[]) entityManager
                .createNativeQuery("SELECT id, branch_id FROM terminals WHERE id = :id AND user_id = :merchantId")
                .setParameter("id", terminalId).setParameter("merchantId", merchantId).getResultStream().findFirst()
                .orElse(null);

        if (terminalData == null) {
            return null;
        }

        Long currentBranchId = terminalData[1] != null ? ((Number) terminalData[1]).longValue() : null;

        // If already assigned to this branch, just return it (idempotent)
        if (currentBranchId != null && currentBranchId.equals(branchId)) {
            return entityManager.find(Terminal.class, terminalId);
        }

        // Update terminal with branch assignment via gRPC to config-service
        // (config-service owns the terminals table; local SQL would be overwritten by
        // replication). This handles both new assignments and reassignments.
        var assignResult = grpcClient.assignTerminalToBranch(terminalId, branchId);
        if (!Boolean.TRUE.equals(assignResult.get("success"))) {
            String msg = (String) assignResult.get("message");
            log.error("Failed to assign terminal {} to branch {} via gRPC: {}", terminalId, branchId, msg);
            throw new AppException("Failed to assign terminal to branch: " + msg, HttpStatus.INTERNAL_SERVER_ERROR);
        }

        if (currentBranchId != null) {
            log.info("Reassigned terminal {} from branch {} to branch {} for merchant {}", terminalId, currentBranchId,
                    branchId, merchantId);
        } else {
            log.info("Assigned terminal {} to branch {} for merchant {}", terminalId, branchId, merchantId);
        }

        // Ensure terminal has a TID mapping for this branch (fail-soft)
        try {
            var result = grpcClient.ensureTidMappingForBranch(terminalId, branchId);
            if (!Boolean.TRUE.equals(result.get("success"))) {
                String msg = (String) result.get("message");
                if (msg != null && msg.contains("No TID found")) {
                    log.warn("Branch {} has no TIDs - terminal {} assigned without TID mapping", branchId, terminalId);
                } else {
                    log.warn("Failed to ensure TID mapping for terminal {} to branch {}: {}", terminalId, branchId,
                            msg);
                }
            }
        } catch (Exception e) {
            log.warn("gRPC call failed for TID mapping (terminal {} to branch {}): {}", terminalId, branchId,
                    e.getMessage());
        }

        // Return the updated terminal
        return entityManager.find(Terminal.class, terminalId);
    }

    /**
     * Unassign a terminal from a branch.
     */
    @Transactional
    public Terminal unassignTerminalFromBranch(Long branchId, Long terminalId, Long merchantId) {
        // Verify terminal is assigned to this branch and merchant
        Long count = ((Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM terminals WHERE id = :terminalId AND branch_id = :branchId AND user_id = :merchantId")
                .setParameter("terminalId", terminalId).setParameter("branchId", branchId)
                .setParameter("merchantId", merchantId).getSingleResult()).longValue();

        if (count == 0) {
            return null;
        }

        // Remove branch assignment via gRPC to config-service
        // (config-service owns the terminals table; local SQL would be overwritten by
        // replication)
        var unassignResult = grpcClient.unassignTerminalFromBranch(terminalId);
        if (!Boolean.TRUE.equals(unassignResult.get("success"))) {
            String msg = (String) unassignResult.get("message");
            log.error("Failed to unassign terminal {} from branch {} via gRPC: {}", terminalId, branchId, msg);
            throw new AppException("Failed to unassign terminal from branch: " + msg, HttpStatus.INTERNAL_SERVER_ERROR);
        }

        log.info("Unassigned terminal {} from branch {} for merchant {}", terminalId, branchId, merchantId);

        return entityManager.find(Terminal.class, terminalId);
    }

    /**
     * Get terminals not assigned to any branch (available for assignment).
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<Terminal> getAvailableTerminals(Long merchantId) {
        String sql = """
                SELECT t.* FROM terminals t
                WHERE t.user_id = :merchantId AND t.branch_id IS NULL
                ORDER BY t.serial
                """;
        return entityManager.createNativeQuery(sql, Terminal.class).setParameter("merchantId", merchantId)
                .getResultList();
    }

    // ────────────────────────────────────────────────────────────── TID
    // Assignment

    /**
     * List TIDs assigned to a branch.
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Map<String, Object> listBranchTids(Long branchId, Long merchantId, Map<String, String> params) {
        // Verify branch exists and belongs to merchant
        Long branchCount = ((Number) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM merchant_branches WHERE id = :id AND user_id = :merchantId")
                .setParameter("id", branchId).setParameter("merchantId", merchantId).getSingleResult()).longValue();

        if (branchCount == 0) {
            return PagedResponse.empty("/branches/" + branchId + "/tids");
        }

        int page = Integer.parseInt(params.getOrDefault("page", "1")) - 1;
        int limit = Integer.parseInt(params.getOrDefault("limit", "15"));

        // Use join table for many-to-many relationship
        StringBuilder where = new StringBuilder(
                "WHERE bt.branch_id = :branchId AND t.user_id = :merchantId AND t.internal = false");
        Map<String, Object> qp = new HashMap<>();
        qp.put("branchId", branchId);
        qp.put("merchantId", merchantId);

        String search = params.get("search");
        if (search != null && !search.isBlank()) {
            where.append(
                    " AND (LOWER(t.terminal_id) LIKE :search OR LOWER(t.merchant_id) LIKE :search OR LOWER(t.merchant_name) LIKE :search)");
            qp.put("search", "%" + search.toLowerCase() + "%");
        }

        // Count query using join table
        String countSql = "SELECT COUNT(*) FROM branch_tids bt JOIN tids t ON t.id = bt.tid_id " + where;
        Query countQ = entityManager.createNativeQuery(countSql);
        qp.forEach(countQ::setParameter);
        long total = ((Number) countQ.getSingleResult()).longValue();

        // Data query using join table
        String dataSql = """
                SELECT t.id, t.terminal_id, t.merchant_id, t.merchant_name,
                       t.bank_acc_no, t.merchant_category_code, t.state_code,
                       t.merchant_physical_addr, t.email, t.mobile_phone,
                       t.created_at, t.updated_at
                FROM branch_tids bt
                JOIN tids t ON t.id = bt.tid_id
                """ + where + " ORDER BY t.terminal_id ASC";

        Query dataQ = entityManager.createNativeQuery(dataSql);
        qp.forEach(dataQ::setParameter);
        dataQ.setFirstResult(page * limit);
        dataQ.setMaxResults(limit);

        List<Object[]> rows = dataQ.getResultList();
        List<Map<String, Object>> out = new ArrayList<>();

        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", num(r[0]));
            m.put("terminal_id", str(r[1]));
            m.put("merchant_id", str(r[2]));
            m.put("merchant_name", str(r[3]));
            m.put("bank_acc_no", str(r[4]));
            m.put("merchant_category_code", str(r[5]));
            m.put("state_code", str(r[6]));
            m.put("merchant_physical_addr", str(r[7]));
            m.put("email", str(r[8]));
            m.put("mobile_phone", str(r[9]));
            m.put("created_at", timestamp(r[10]));
            m.put("updated_at", timestamp(r[11]));
            out.add(m);
        }

        Page<Map<String, Object>> pageResult = new PageImpl<>(out, PageRequest.of(page, limit), total);
        return PagedResponse.from(pageResult, "/branches/" + branchId + "/tids");
    }

    /**
     * Assign a TID to a branch. One TID can be assigned to multiple branches
     * simultaneously via the branch_tids join table.
     */
    @Transactional
    public Map<String, Object> assignTidToBranch(Long branchId, Long tidId, Long merchantId) {
        // Verify branch exists and belongs to merchant
        Long branchCount = ((Number) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM merchant_branches WHERE id = :id AND user_id = :merchantId")
                .setParameter("id", branchId).setParameter("merchantId", merchantId).getSingleResult()).longValue();

        if (branchCount == 0) {
            return null;
        }

        // Verify TID exists and belongs to merchant
        Long tidExists = ((Number) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM tids WHERE id = :id AND user_id = :merchantId")
                .setParameter("id", tidId).setParameter("merchantId", merchantId).getSingleResult()).longValue();

        if (tidExists == 0) {
            return null;
        }

        // Check if already assigned to this branch (via join table)
        Long alreadyAssigned = ((Number) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM branch_tids WHERE tid_id = :tidId AND branch_id = :branchId")
                .setParameter("tidId", tidId).setParameter("branchId", branchId).getSingleResult()).longValue();

        // If already assigned to this branch, just return it (idempotent)
        if (alreadyAssigned > 0) {
            return getTidById(tidId);
        }

        // Create assignment via gRPC (writes to config-service branch_tids table)
        var assignResult = grpcClient.assignTidToBranch(tidId, branchId);
        if (!Boolean.TRUE.equals(assignResult.get("success"))) {
            String msg = assignResult.get("message") != null ? assignResult.get("message").toString() : "Unknown error";
            throw new AppException("Failed to assign TID to branch: " + msg, HttpStatus.INTERNAL_SERVER_ERROR);
        }

        // Also write locally since replication isn't set up for branch_tids yet.
        // Use INSERT ... ON CONFLICT for idempotency.
        entityManager.createNativeQuery("""
                INSERT INTO branch_tids (branch_id, tid_id, created_at)
                VALUES (:branchId, :tidId, NOW())
                ON CONFLICT (branch_id, tid_id) DO NOTHING
                """).setParameter("branchId", branchId).setParameter("tidId", tidId).executeUpdate();

        log.info("Assigned TID {} to branch {} for merchant {}", tidId, branchId, merchantId);

        return getTidById(tidId);
    }

    /**
     * Unassign a TID from a branch. Removes the assignment from the branch_tids
     * join table. The TID may still be assigned to other branches.
     */
    @Transactional
    public Map<String, Object> unassignTidFromBranch(Long branchId, Long tidId, Long merchantId) {
        // Verify TID exists and belongs to this merchant
        Long tidExists = ((Number) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM tids WHERE id = :tidId AND user_id = :merchantId")
                .setParameter("tidId", tidId).setParameter("merchantId", merchantId).getSingleResult()).longValue();

        if (tidExists == 0) {
            return null;
        }

        // Verify TID is assigned to this branch (via join table)
        Long assigned = ((Number) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM branch_tids WHERE tid_id = :tidId AND branch_id = :branchId")
                .setParameter("tidId", tidId).setParameter("branchId", branchId).getSingleResult()).longValue();

        if (assigned == 0) {
            return null;
        }

        // Remove branch assignment via gRPC (writes to config-service)
        var unassignResult = grpcClient.unassignTidFromBranch(tidId, branchId);
        if (!Boolean.TRUE.equals(unassignResult.get("success"))) {
            String msg = unassignResult.get("message") != null
                    ? unassignResult.get("message").toString()
                    : "Unknown error";
            throw new AppException("Failed to unassign TID from branch: " + msg, HttpStatus.INTERNAL_SERVER_ERROR);
        }

        // Also delete locally since replication isn't set up for branch_tids yet
        entityManager.createNativeQuery("DELETE FROM branch_tids WHERE branch_id = :branchId AND tid_id = :tidId")
                .setParameter("branchId", branchId).setParameter("tidId", tidId).executeUpdate();

        log.info("Unassigned TID {} from branch {} for merchant {}", tidId, branchId, merchantId);

        return getTidById(tidId);
    }

    /**
     * Get TIDs available for assignment to a branch. Returns ALL non-internal TIDs
     * for the merchant. Since one TID can be assigned to multiple branches via the
     * branch_tids join table, this returns all TIDs with a count of how many
     * branches each is currently assigned to, and whether it's assigned to the
     * current branch (if branchId is provided).
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getAvailableTids(Long merchantId) {
        return getAvailableTids(merchantId, null);
    }

    /**
     * Get TIDs available for assignment to a branch with context about the current
     * branch.
     *
     * @param merchantId
     *            The merchant ID
     * @param branchId
     *            Optional branch ID - if provided, includes whether each TID is
     *            assigned to this specific branch
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getAvailableTids(Long merchantId, Long branchId) {
        String sql = """
                SELECT t.id, t.terminal_id, t.merchant_id, t.merchant_name,
                       t.bank_acc_no, t.merchant_category_code, t.state_code,
                       t.merchant_physical_addr, t.email, t.mobile_phone,
                       t.created_at, t.updated_at,
                       (SELECT COUNT(*) FROM branch_tids bt WHERE bt.tid_id = t.id) as assigned_branches,
                       (SELECT COUNT(*) > 0 FROM branch_tids bt WHERE bt.tid_id = t.id AND bt.branch_id = :branchId) as assigned_to_this_branch
                FROM tids t
                WHERE t.user_id = :merchantId AND t.internal = false
                ORDER BY t.terminal_id
                """;
        List<Object[]> rows = entityManager.createNativeQuery(sql).setParameter("merchantId", merchantId)
                .setParameter("branchId", branchId != null ? branchId : -1L).getResultList();

        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", num(r[0]));
            m.put("terminal_id", str(r[1]));
            m.put("merchant_id", str(r[2]));
            m.put("merchant_name", str(r[3]));
            m.put("bank_acc_no", str(r[4]));
            m.put("merchant_category_code", str(r[5]));
            m.put("state_code", str(r[6]));
            m.put("merchant_physical_addr", str(r[7]));
            m.put("email", str(r[8]));
            m.put("mobile_phone", str(r[9]));
            m.put("created_at", timestamp(r[10]));
            m.put("updated_at", timestamp(r[11]));
            m.put("assigned_branches", num(r[12]));
            m.put("assigned_to_this_branch", Boolean.TRUE.equals(r[13]));
            out.add(m);
        }
        return out;
    }

    /**
     * Get a single TID by ID (helper method).
     */
    @Transactional(readOnly = true)
    private Map<String, Object> getTidById(Long tidId) {
        try {
            Object[] r = (Object[]) entityManager.createNativeQuery("""
                    SELECT t.id, t.terminal_id, t.merchant_id, t.merchant_name,
                           t.bank_acc_no, t.merchant_category_code, t.state_code,
                           t.merchant_physical_addr, t.email, t.mobile_phone,
                           t.created_at, t.updated_at
                    FROM tids t WHERE t.id = :tidId
                    """).setParameter("tidId", tidId).getSingleResult();

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", num(r[0]));
            m.put("terminal_id", str(r[1]));
            m.put("merchant_id", str(r[2]));
            m.put("merchant_name", str(r[3]));
            m.put("bank_acc_no", str(r[4]));
            m.put("merchant_category_code", str(r[5]));
            m.put("state_code", str(r[6]));
            m.put("merchant_physical_addr", str(r[7]));
            m.put("email", str(r[8]));
            m.put("mobile_phone", str(r[9]));
            m.put("created_at", timestamp(r[10]));
            m.put("updated_at", timestamp(r[11]));
            return m;
        } catch (jakarta.persistence.NoResultException e) {
            return null;
        }
    }

    // ────────────────────────────────────────────────────────────── Branch
    // Transactions

    /**
     * List transactions for terminals assigned to a branch.
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Map<String, Object> listBranchTransactions(Long branchId, Long merchantId, Map<String, String> params) {
        // Verify branch exists and belongs to merchant
        Long branchCount = ((Number) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM merchant_branches WHERE id = :id AND user_id = :merchantId")
                .setParameter("id", branchId).setParameter("merchantId", merchantId).getSingleResult()).longValue();

        if (branchCount == 0) {
            return PagedResponse.empty("/branches/" + branchId + "/transactions");
        }

        int page = Integer.parseInt(params.getOrDefault("page", "1")) - 1;
        int limit = Integer.parseInt(params.getOrDefault("limit", "15"));

        // Branch filtering: prefer the dedicated branch_id column stamped at
        // transaction time, which captures the terminal's branch assignment when
        // the transaction occurred. Fall back to the terminal's current branch
        // for older transactions that don't have branch_id set.
        StringBuilder where = new StringBuilder("""
                WHERE tx.user_id = :merchantId
                  AND (
                      tx.branch_id = :branchId
                      OR (tx.branch_id IS NULL AND EXISTS (
                          SELECT 1 FROM terminals t
                          WHERE t.serial = tx.terminal_id AND t.branch_id = :branchId
                      ))
                  )
                """);
        Map<String, Object> qp = new HashMap<>();
        qp.put("branchId", branchId);
        qp.put("merchantId", merchantId);

        String search = params.get("search");
        if (search != null && !search.isBlank()) {
            where.append(" AND (tx.reference LIKE :search)");
            qp.put("search", "%" + search + "%");
        }

        String status = params.get("status");
        if (status != null && !status.isBlank()) {
            where.append(" AND tx.status_code = :status");
            qp.put("status", status.toLowerCase());
        }

        String paymentMethod = params.get("payment_method");
        if (paymentMethod != null && !paymentMethod.isBlank()) {
            where.append(" AND tx.payment_method = :paymentMethod");
            qp.put("paymentMethod", paymentMethod);
        }

        // Count query
        String countSql = "SELECT COUNT(*) FROM transactions tx " + where;
        Query countQ = entityManager.createNativeQuery(countSql);
        qp.forEach(countQ::setParameter);
        long total = ((Number) countQ.getSingleResult()).longValue();

        // Data query
        String dataSql = """
                SELECT tx.id, tx.reference, tx.amount, tx.status_code, tx.status_message,
                       tx.payment_method, tx.channel, tx.terminal_id,
                       tx.created_at, tx.updated_at,
                       p.name as product_name,
                       prov.name as provider_name
                FROM transactions tx
                LEFT JOIN products p ON p.id = tx.product_id
                LEFT JOIN providers prov ON prov.id = tx.provider_id
                """ + where + " ORDER BY tx.created_at DESC";

        Query dataQ = entityManager.createNativeQuery(dataSql);
        qp.forEach(dataQ::setParameter);
        dataQ.setFirstResult(page * limit);
        dataQ.setMaxResults(limit);

        List<Object[]> rows = dataQ.getResultList();
        List<Map<String, Object>> out = new ArrayList<>();

        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", num(r[0]));
            m.put("reference", str(r[1]));
            m.put("amount", r[2]);
            m.put("status_code", str(r[3]));
            m.put("status", formatStatus(str(r[3])));
            m.put("status_message", str(r[4]));
            m.put("payment_method", str(r[5]));
            m.put("channel", str(r[6]));
            m.put("terminal_id", str(r[7]));
            m.put("created_at", timestamp(r[8]));
            m.put("updated_at", timestamp(r[9]));
            m.put("product", str(r[10]));
            m.put("provider", str(r[11]));
            out.add(m);
        }

        Page<Map<String, Object>> pageResult = new PageImpl<>(out, PageRequest.of(page, limit), total);
        return PagedResponse.from(pageResult, "/branches/" + branchId + "/transactions");
    }

    // ────────────────────────────────────────────────────────────── Helpers

    private String formatStatus(String status) {
        if (status == null)
            return "Active";
        return status.substring(0, 1).toUpperCase() + status.substring(1).toLowerCase();
    }

    private Long num(Object o) {
        if (o == null)
            return null;
        if (o instanceof Number)
            return ((Number) o).longValue();
        return Long.parseLong(o.toString());
    }

    private String str(Object o) {
        return o != null ? o.toString() : null;
    }

    private String timestamp(Object o) {
        if (o == null)
            return null;
        if (o instanceof Timestamp)
            return ((Timestamp) o).toInstant().toString();
        return o.toString();
    }

    private Instant toInstant(Object o) {
        if (o == null)
            return null;
        if (o instanceof Timestamp)
            return ((Timestamp) o).toInstant();
        if (o instanceof java.time.LocalDateTime ldt)
            return ldt.atZone(java.time.ZoneId.systemDefault()).toInstant();
        if (o instanceof java.time.OffsetDateTime odt)
            return odt.toInstant();
        // Fallback: try parsing as ISO instant, or as local datetime
        String s = o.toString();
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            // Try parsing as LocalDateTime and convert
            return java.time.LocalDateTime.parse(s).atZone(java.time.ZoneId.systemDefault()).toInstant();
        }
    }
}

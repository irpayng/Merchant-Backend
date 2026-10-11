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
 * Branches are stored in the config database (merchant_branches table). Branch
 * queries execute via native SQL against the shared config schema, while
 * settlement account validation uses gRPC to tms-config.
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BranchService {

    private final EntityManager entityManager;
    private final GrpcClient grpcClient;

    /**
     * List branches for a merchant with pagination and filtering.
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Page<Map<String, Object>> listBranches(Long merchantId, Map<String, String> params) {
        int page = Integer.parseInt(params.getOrDefault("page", "1")) - 1;
        int limit = Integer.parseInt(params.getOrDefault("limit", "15"));

        StringBuilder where = new StringBuilder("WHERE b.user_id = :merchantId");
        Map<String, Object> qp = new HashMap<>();
        qp.put("merchantId", merchantId);

        String search = params.get("search");
        if (search != null && !search.isBlank()) {
            where.append(" AND (LOWER(b.name) LIKE :search OR LOWER(b.address) LIKE :search OR b.code LIKE :search)");
            qp.put("search", "%" + search.toLowerCase() + "%");
        }

        String status = params.get("status");
        if (status != null && !status.isBlank()) {
            where.append(" AND b.status = :status");
            qp.put("status", status.toLowerCase());
        }

        // Count query
        String countSql = "SELECT COUNT(*) FROM merchant_branches b " + where;
        Query countQ = entityManager.createNativeQuery(countSql);
        qp.forEach(countQ::setParameter);
        long total = ((Number) countQ.getSingleResult()).longValue();

        // Data query with terminal count and volume
        String dataSql = """
                SELECT b.id, b.name, b.code, b.address, b.state_code, b.lga_code,
                       b.phone_number, b.email, b.status, b.is_primary,
                       b.created_at, b.updated_at,
                       COALESCE(tc.terminal_count, 0) as terminals,
                       COALESCE(tv.total_volume, 0) as total_volume
                FROM merchant_branches b
                LEFT JOIN (
                    SELECT branch_id, COUNT(*) as terminal_count
                    FROM tids
                    WHERE branch_id IS NOT NULL
                    GROUP BY branch_id
                ) tc ON tc.branch_id = b.id
                LEFT JOIN (
                    SELECT t.branch_id, SUM(tx.amount) as total_volume
                    FROM tids t
                    JOIN transactions tx ON tx.terminal_id = t.terminal_id
                    WHERE t.branch_id IS NOT NULL AND tx.status_code = 'successful'
                    GROUP BY t.branch_id
                ) tv ON tv.branch_id = b.id
                """ + where + " ORDER BY b.is_primary DESC, b.name ASC";

        Query dataQ = entityManager.createNativeQuery(dataSql);
        qp.forEach(dataQ::setParameter);
        dataQ.setFirstResult(page * limit);
        dataQ.setMaxResults(limit);

        List<Object[]> rows = dataQ.getResultList();
        List<Map<String, Object>> out = new ArrayList<>();

        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", num(r[0]));
            m.put("branch_id", String.valueOf(num(r[0])));
            m.put("name", str(r[1]));
            m.put("code", str(r[2]));
            m.put("address", str(r[3]));
            m.put("location", str(r[3])); // alias for frontend compatibility
            m.put("state_code", str(r[4]));
            m.put("lga_code", str(r[5]));
            m.put("phone_number", str(r[6]));
            m.put("email", str(r[7]));
            m.put("status", formatStatus(str(r[8])));
            m.put("is_primary", r[9]);
            m.put("created_at", timestamp(r[10]));
            m.put("updated_at", timestamp(r[11]));
            m.put("terminals", num(r[12]));
            m.put("total_volume", num(r[13]));
            out.add(m);
        }

        return new PageImpl<>(out, PageRequest.of(page, limit), total);
    }

    /**
     * Get a single branch by ID.
     */
    @Transactional(readOnly = true)
    public BranchResponse getBranch(Long branchId, Long merchantId) {
        String sql = """
                SELECT b.id, b.name, b.code, b.address, b.state_code, b.lga_code,
                       b.phone_number, b.email, b.status, b.is_primary,
                       b.created_at, b.updated_at,
                       COALESCE(tc.terminal_count, 0) as terminals,
                       COALESCE(tv.total_volume, 0) as total_volume,
                       b.settlement_account_id
                FROM merchant_branches b
                LEFT JOIN (
                    SELECT branch_id, COUNT(*) as terminal_count
                    FROM tids WHERE branch_id = :branchId
                    GROUP BY branch_id
                ) tc ON tc.branch_id = b.id
                LEFT JOIN (
                    SELECT t.branch_id, SUM(tx.amount) as total_volume
                    FROM tids t
                    JOIN transactions tx ON tx.terminal_id = t.terminal_id
                    WHERE t.branch_id = :branchId AND tx.status_code = 'successful'
                    GROUP BY t.branch_id
                ) tv ON tv.branch_id = b.id
                WHERE b.id = :branchId AND b.user_id = :merchantId
                """;

        try {
            Object[] r = (Object[]) entityManager.createNativeQuery(sql).setParameter("branchId", branchId)
                    .setParameter("merchantId", merchantId).getSingleResult();

            Long settlementAccountId = num(r[14]);

            // Fetch settlement account details via gRPC if account is assigned
            String accountNumber = null;
            String accountName = null;
            String bankCode = null;
            String bankName = null;

            if (settlementAccountId != null) {
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

            return BranchResponse.builder().id(num(r[0])).branchId(String.valueOf(num(r[0]))).name(str(r[1]))
                    .code(str(r[2])).address(str(r[3])).location(str(r[3])).stateCode(str(r[4])).lgaCode(str(r[5]))
                    .phoneNumber(str(r[6])).email(str(r[7])).status(formatStatus(str(r[8])))
                    .isPrimary(Boolean.TRUE.equals(r[9])).createdAt(toInstant(r[10])).updatedAt(toInstant(r[11]))
                    .terminals(num(r[12]) != null ? num(r[12]).intValue() : 0)
                    .totalVolume(num(r[13]) != null ? num(r[13]) : 0L).settlementAccountId(settlementAccountId)
                    .settlementAccountNumber(accountNumber).settlementAccountName(accountName)
                    .settlementBankCode(bankCode).settlementBankName(bankName).build();
        } catch (jakarta.persistence.NoResultException e) {
            return null;
        }
    }

    /**
     * Create a new branch.
     */
    @Transactional
    public BranchResponse createBranch(Long merchantId, BranchCreateRequest request) {
        // Generate code if not provided
        String code = request.getCode();
        if (code == null || code.isBlank()) {
            code = generateBranchCode(merchantId);
        }

        // If setting as primary, clear existing primary
        if (Boolean.TRUE.equals(request.getIsPrimary())) {
            entityManager.createNativeQuery(
                    "UPDATE merchant_branches SET is_primary = false WHERE user_id = :merchantId AND is_primary = true")
                    .setParameter("merchantId", merchantId).executeUpdate();
        }

        String sql = """
                INSERT INTO merchant_branches
                (user_id, name, code, address, state_code, lga_code, phone_number, email, status, is_primary, created_at, updated_at)
                VALUES (:userId, :name, :code, :address, :stateCode, :lgaCode, :phone, :email, 'active', :isPrimary, NOW(), NOW())
                RETURNING id
                """;

        Long branchId = ((Number) entityManager.createNativeQuery(sql).setParameter("userId", merchantId)
                .setParameter("name", request.getName()).setParameter("code", code)
                .setParameter("address", request.getAddress() != null ? request.getAddress() : request.getLocation())
                .setParameter("stateCode", request.getStateCode()).setParameter("lgaCode", request.getLgaCode())
                .setParameter("phone", request.getPhoneNumber()).setParameter("email", request.getEmail())
                .setParameter("isPrimary", Boolean.TRUE.equals(request.getIsPrimary())).getSingleResult()).longValue();

        log.info("Created branch {} for merchant {}", branchId, merchantId);
        return getBranch(branchId, merchantId);
    }

    /**
     * Update an existing branch.
     */
    @Transactional
    public BranchResponse updateBranch(Long branchId, Long merchantId, BranchUpdateRequest request) {
        // Verify branch exists and belongs to merchant
        Long count = ((Number) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM merchant_branches WHERE id = :id AND user_id = :merchantId")
                .setParameter("id", branchId).setParameter("merchantId", merchantId).getSingleResult()).longValue();

        if (count == 0) {
            return null;
        }

        StringBuilder sql = new StringBuilder("UPDATE merchant_branches SET updated_at = NOW()");
        Map<String, Object> params = new HashMap<>();
        params.put("id", branchId);
        params.put("merchantId", merchantId);

        if (request.getName() != null) {
            sql.append(", name = :name");
            params.put("name", request.getName());
        }
        if (request.getAddress() != null) {
            sql.append(", address = :address");
            params.put("address", request.getAddress());
        }
        if (request.getLocation() != null && request.getAddress() == null) {
            sql.append(", address = :address");
            params.put("address", request.getLocation());
        }
        if (request.getStateCode() != null) {
            sql.append(", state_code = :stateCode");
            params.put("stateCode", request.getStateCode());
        }
        if (request.getLgaCode() != null) {
            sql.append(", lga_code = :lgaCode");
            params.put("lgaCode", request.getLgaCode());
        }
        if (request.getPhoneNumber() != null) {
            sql.append(", phone_number = :phone");
            params.put("phone", request.getPhoneNumber());
        }
        if (request.getEmail() != null) {
            sql.append(", email = :email");
            params.put("email", request.getEmail());
        }
        if (request.getStatus() != null) {
            sql.append(", status = :status");
            params.put("status", request.getStatus().toLowerCase());
        }

        sql.append(" WHERE id = :id AND user_id = :merchantId");

        Query q = entityManager.createNativeQuery(sql.toString());
        params.forEach(q::setParameter);
        q.executeUpdate();

        log.info("Updated branch {} for merchant {}", branchId, merchantId);
        return getBranch(branchId, merchantId);
    }

    /**
     * Set a branch as primary.
     */
    @Transactional
    public BranchResponse setPrimary(Long branchId, Long merchantId) {
        // Verify branch exists
        Long count = ((Number) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM merchant_branches WHERE id = :id AND user_id = :merchantId")
                .setParameter("id", branchId).setParameter("merchantId", merchantId).getSingleResult()).longValue();

        if (count == 0) {
            return null;
        }

        // Clear existing primary
        entityManager.createNativeQuery(
                "UPDATE merchant_branches SET is_primary = false WHERE user_id = :merchantId AND is_primary = true")
                .setParameter("merchantId", merchantId).executeUpdate();

        // Set new primary
        entityManager
                .createNativeQuery("UPDATE merchant_branches SET is_primary = true, updated_at = NOW() WHERE id = :id")
                .setParameter("id", branchId).executeUpdate();

        log.info("Set branch {} as primary for merchant {}", branchId, merchantId);
        return getBranch(branchId, merchantId);
    }

    /**
     * Delete a branch.
     */
    @Transactional
    public boolean deleteBranch(Long branchId, Long merchantId) {
        // Check if branch has TIDs assigned
        Long tidCount = ((Number) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM tids WHERE branch_id = :branchId")
                .setParameter("branchId", branchId).getSingleResult()).longValue();

        if (tidCount > 0) {
            log.warn("Cannot delete branch {} - has {} TIDs assigned", branchId, tidCount);
            return false;
        }

        int deleted = entityManager
                .createNativeQuery("DELETE FROM merchant_branches WHERE id = :id AND user_id = :merchantId")
                .setParameter("id", branchId).setParameter("merchantId", merchantId).executeUpdate();

        if (deleted > 0) {
            log.info("Deleted branch {} for merchant {}", branchId, merchantId);
        }
        return deleted > 0;
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

        // Terminal count
        Long terminals = ((Number) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM tids WHERE branch_id = :branchId")
                .setParameter("branchId", branchId).getSingleResult()).longValue();
        stats.put("terminals", terminals);

        // Transaction stats (30 days)
        try {
            Object[] txStats = (Object[]) entityManager.createNativeQuery("""
                    SELECT COUNT(*), COALESCE(SUM(tx.amount), 0)
                    FROM transactions tx
                    JOIN tids t ON t.terminal_id = tx.terminal_id
                    WHERE t.branch_id = :branchId
                      AND tx.created_at >= NOW() - INTERVAL '30 days'
                      AND tx.status_code = 'successful'
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
        // Verify branch exists and belongs to merchant
        Long branchCount = ((Number) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM merchant_branches WHERE id = :id AND user_id = :merchantId")
                .setParameter("id", branchId).setParameter("merchantId", merchantId).getSingleResult()).longValue();

        if (branchCount == 0) {
            log.warn("Branch {} not found for merchant {}", branchId, merchantId);
            return null;
        }

        // Verify settlement account exists and belongs to merchant via gRPC
        // The settlement accounts are stored in tms-config, not in the merchant
        // database
        List<Map<String, Object>> accounts = grpcClient.listMerchantSettlementAccounts(merchantId);
        boolean accountValid = accounts.stream()
                .anyMatch(acct -> settlementAccountId.equals(((Number) acct.get("id")).longValue())
                        && "active".equals(acct.get("status")));

        if (!accountValid) {
            log.warn("Settlement account {} not found or not active for merchant {}", settlementAccountId, merchantId);
            return null;
        }

        String sql = """
                UPDATE merchant_branches SET
                    settlement_account_id = :accountId,
                    updated_at = NOW()
                WHERE id = :id AND user_id = :merchantId
                """;

        entityManager.createNativeQuery(sql).setParameter("accountId", settlementAccountId).setParameter("id", branchId)
                .setParameter("merchantId", merchantId).executeUpdate();

        log.info("Assigned settlement account {} to branch {} for merchant {}", settlementAccountId, branchId,
                merchantId);
        return getBranch(branchId, merchantId);
    }

    /**
     * Clear branch settlement account (fall back to merchant default).
     */
    @Transactional
    public BranchResponse clearSettlementAccount(Long branchId, Long merchantId) {
        // Verify branch exists and belongs to merchant
        Long count = ((Number) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM merchant_branches WHERE id = :id AND user_id = :merchantId")
                .setParameter("id", branchId).setParameter("merchantId", merchantId).getSingleResult()).longValue();

        if (count == 0) {
            return null;
        }

        String sql = """
                UPDATE merchant_branches SET
                    settlement_account_id = NULL,
                    updated_at = NOW()
                WHERE id = :id AND user_id = :merchantId
                """;

        entityManager.createNativeQuery(sql).setParameter("id", branchId).setParameter("merchantId", merchantId)
                .executeUpdate();

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

        // Data query with latest metrics
        String dataSql = """
                SELECT t.id, t.serial, t.make, t.model, t.os, t.active, t.locked,
                       t.created_at, t.updated_at,
                       m.battery_pct, m.network_type, m.printer_status, m.created_at as last_seen,
                       COALESCE(loc.location, t.serial) as location
                FROM terminals t
                LEFT JOIN LATERAL (
                    SELECT battery_pct, network_type, printer_status, created_at
                    FROM terminal_metrics
                    WHERE serial = t.serial
                    ORDER BY created_at DESC
                    LIMIT 1
                ) m ON true
                LEFT JOIN (
                    SELECT terminal_address as location, terminal_id
                    FROM tids
                ) loc ON loc.terminal_id = t.serial
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
            m.put("terminal_id", str(r[1]));
            m.put("serial", str(r[1]));
            m.put("make", str(r[2]));
            m.put("model", str(r[3]));
            m.put("os", str(r[4]));
            m.put("model_os", str(r[3]) + ", " + str(r[4]));
            m.put("active", r[5]);
            m.put("locked", r[6]);
            m.put("status", Boolean.TRUE.equals(r[6]) ? "Locked" : "Online");
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
     * Assign a terminal to a branch. Returns null if the terminal is already
     * assigned to a different branch (must unassign first).
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

        // Verify terminal exists, belongs to merchant, and is not assigned to another
        // branch
        Object[] terminalData = (Object[]) entityManager
                .createNativeQuery("SELECT id, branch_id FROM terminals WHERE id = :id AND user_id = :merchantId")
                .setParameter("id", terminalId).setParameter("merchantId", merchantId).getResultStream().findFirst()
                .orElse(null);

        if (terminalData == null) {
            return null;
        }

        Long currentBranchId = terminalData[1] != null ? ((Number) terminalData[1]).longValue() : null;

        // If terminal is already assigned to a different branch, reject the assignment
        if (currentBranchId != null && !currentBranchId.equals(branchId)) {
            log.warn("Cannot assign terminal {} to branch {} - already assigned to branch {}", terminalId, branchId,
                    currentBranchId);
            throw new AppException("Terminal is already assigned to another branch. Unassign it first.",
                    HttpStatus.CONFLICT);
        }

        // If already assigned to this branch, just return it (idempotent)
        if (currentBranchId != null && currentBranchId.equals(branchId)) {
            return entityManager.find(Terminal.class, terminalId);
        }

        // Update terminal with branch assignment
        entityManager
                .createNativeQuery(
                        "UPDATE terminals SET branch_id = :branchId, updated_at = NOW() WHERE id = :terminalId")
                .setParameter("branchId", branchId).setParameter("terminalId", terminalId).executeUpdate();

        log.info("Assigned terminal {} to branch {} for merchant {}", terminalId, branchId, merchantId);

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

        // Remove branch assignment
        entityManager
                .createNativeQuery("UPDATE terminals SET branch_id = NULL, updated_at = NOW() WHERE id = :terminalId")
                .setParameter("terminalId", terminalId).executeUpdate();

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

        StringBuilder where = new StringBuilder("""
                WHERE tx.user_id = :merchantId
                  AND EXISTS (
                      SELECT 1 FROM terminals t
                      WHERE t.serial = tx.terminal_id AND t.branch_id = :branchId
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

    private String generateBranchCode(Long merchantId) {
        Long count = ((Number) entityManager
                .createNativeQuery("SELECT COUNT(*) + 1 FROM merchant_branches WHERE user_id = :merchantId")
                .setParameter("merchantId", merchantId).getSingleResult()).longValue();
        return "BR" + String.format("%03d", count);
    }

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

package com.tms.report.modules.branch.service;

import com.tms.report.modules.branch.dto.BranchCreateRequest;
import com.tms.report.modules.branch.dto.BranchResponse;
import com.tms.report.modules.branch.dto.BranchUpdateRequest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for merchant branch management.
 *
 * <p>
 * Branches are stored in the config schema (merchant_branches table) and
 * queried directly via native SQL since this is a Spring Boot app without the
 * Quarkus entity.
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BranchService {

    private final EntityManager entityManager;

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
                       COALESCE(tv.total_volume, 0) as total_volume
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

            return BranchResponse.builder().id(num(r[0])).branchId(String.valueOf(num(r[0]))).name(str(r[1]))
                    .code(str(r[2])).address(str(r[3])).location(str(r[3])).stateCode(str(r[4])).lgaCode(str(r[5]))
                    .phoneNumber(str(r[6])).email(str(r[7])).status(formatStatus(str(r[8])))
                    .isPrimary(Boolean.TRUE.equals(r[9])).createdAt(toInstant(r[10])).updatedAt(toInstant(r[11]))
                    .terminals(num(r[12]) != null ? num(r[12]).intValue() : 0)
                    .totalVolume(num(r[13]) != null ? num(r[13]) : 0L).build();
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
        return Instant.parse(o.toString());
    }
}

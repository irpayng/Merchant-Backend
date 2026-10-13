package com.tms.report.modules.settlement.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tms.report.modules.settlement.dto.ApprovalRequestResponse;
import com.tms.report.modules.settlement.dto.SettlementAccountRequestDto;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Service for handling merchant-initiated settlement approval requests. Writes
 * directly to supermerchant.pending_approvals using native SQL since
 * Merchant-Backend does not have JPA entities for that schema.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SettlementApprovalService {

    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;

    /**
     * Get merchant name and email from users/profiles tables.
     *
     * @param merchantId
     *            the merchant's user ID
     * @return map with "name" and "email" keys
     */
    public Map<String, String> getMerchantInfo(Long merchantId) {
        String sql = """
                SELECT COALESCE(NULLIF(TRIM(COALESCE(p.first_name, '') || ' ' || COALESCE(p.last_name, '')), ''), u.email) as name,
                       u.email
                FROM users u
                LEFT JOIN profiles p ON p.user_id = u.id
                WHERE u.id = :merchantId
                """;

        try {
            Object[] row = (Object[]) entityManager.createNativeQuery(sql).setParameter("merchantId", merchantId)
                    .getSingleResult();

            Map<String, String> info = new LinkedHashMap<>();
            info.put("name", row[0] != null ? row[0].toString() : null);
            info.put("email", row[1] != null ? row[1].toString() : null);
            return info;
        } catch (jakarta.persistence.NoResultException e) {
            log.warn("No user found for merchantId {}", merchantId);
            return Map.of("name", "", "email", "");
        }
    }

    /**
     * Resolve the bank_code for a merchant from their TID assignments.
     *
     * @param merchantId
     *            the merchant's user ID
     * @return the bank_code or null if no TID found
     */
    public String resolveBankCode(Long merchantId) {
        String sql = """
                SELECT DISTINCT bank_code FROM tids
                WHERE user_id = :merchantId AND bank_code IS NOT NULL
                LIMIT 1
                """;

        try {
            Object result = entityManager.createNativeQuery(sql).setParameter("merchantId", merchantId)
                    .getSingleResult();
            return result != null ? result.toString() : null;
        } catch (jakarta.persistence.NoResultException e) {
            return null;
        }
    }

    /**
     * Check if a pending approval already exists for this merchant's account
     * section.
     *
     * @param merchantId
     *            the merchant's user ID
     * @return true if a pending request exists
     */
    public boolean hasPendingAccountRequest(Long merchantId) {
        String sql = """
                SELECT COUNT(*) FROM supermerchant.pending_approvals
                WHERE user_id = :merchantId AND section = 'account' AND status = 'pending'
                """;

        Long count = ((Number) entityManager.createNativeQuery(sql).setParameter("merchantId", merchantId)
                .getSingleResult()).longValue();

        return count > 0;
    }

    /**
     * Check if a pending approval exists for a merchant and specific section.
     *
     * @param merchantId
     *            the merchant's user ID
     * @param section
     *            the section to check (e.g., 'account', 'schedule', 'fees')
     * @return true if a pending request exists for the given section
     */
    public boolean hasPendingRequest(Long merchantId, String section) {
        String sql = """
                SELECT COUNT(*) FROM supermerchant.pending_approvals
                WHERE user_id = :merchantId AND section = :section AND status = 'pending'
                """;

        Long count = ((Number) entityManager.createNativeQuery(sql).setParameter("merchantId", merchantId)
                .setParameter("section", section).getSingleResult()).longValue();

        return count > 0;
    }

    /**
     * Get all approval requests for a merchant, ordered by creation date
     * descending.
     *
     * @param merchantId
     *            the merchant's user ID
     * @return list of approval requests
     */
    @SuppressWarnings("unchecked")
    public List<ApprovalRequestResponse> getApprovalRequests(Long merchantId) {
        String sql = """
                SELECT id, user_id, section, status, description, payload,
                       reject_reason, created_at, approved_at, rejected_at, executed_at
                FROM supermerchant.pending_approvals
                WHERE user_id = :merchantId AND action_type = 'settlement_config'
                ORDER BY created_at DESC
                """;

        List<Object[]> rows = entityManager.createNativeQuery(sql).setParameter("merchantId", merchantId)
                .getResultList();

        List<ApprovalRequestResponse> results = new ArrayList<>();
        for (Object[] row : rows) {
            results.add(ApprovalRequestResponse.builder().id(longVal(row[0])).userId(longVal(row[1]))
                    .section(strVal(row[2])).status(strVal(row[3])).description(strVal(row[4])).payload(strVal(row[5]))
                    .rejectReason(strVal(row[6])).createdAt(timestampToLocalDateTime(row[7]))
                    .approvedAt(timestampToLocalDateTime(row[8])).rejectedAt(timestampToLocalDateTime(row[9]))
                    .executedAt(timestampToLocalDateTime(row[10])).build());
        }
        return results;
    }

    private static Long longVal(Object o) {
        return o != null ? ((Number) o).longValue() : null;
    }

    private static String strVal(Object o) {
        return o != null ? o.toString() : null;
    }

    private static LocalDateTime timestampToLocalDateTime(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Timestamp ts) {
            return ts.toLocalDateTime();
        }
        return null;
    }

    /**
     * Submit a settlement account change request for merchant approval. Creates a
     * pending_approvals record that the super-merchant (bank) will approve.
     *
     * @param merchantId
     *            the merchant's user ID
     * @param request
     *            the account change request details
     * @param merchantName
     *            the merchant's display name
     * @param merchantEmail
     *            the merchant's email address
     * @return the approval ID, or null if failed
     */
    public Long submitAccountChangeRequest(Long merchantId, SettlementAccountRequestDto request, String merchantName,
            String merchantEmail) {

        // Resolve bank_code from merchant's TID assignments
        String bankCode = resolveBankCode(merchantId);
        if (bankCode == null) {
            log.warn("Cannot submit account change request - no bank_code found for merchant {}", merchantId);
            return null;
        }

        // Check for existing pending request
        if (hasPendingAccountRequest(merchantId)) {
            log.warn("Merchant {} already has a pending account change request", merchantId);
            return null;
        }

        // Build payload JSON
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bank_name", request.getBankName());
        payload.put("account_number", request.getAccountNumber());
        payload.put("account_name", request.getAccountName());
        payload.put("account_type", request.getAccountType());

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize payload for merchant {}", merchantId, e);
            return null;
        }

        String description = "Settlement account change request: " + request.getBankName() + " - "
                + request.getAccountNumber();

        // Insert into supermerchant.pending_approvals
        String sql = """
                INSERT INTO supermerchant.pending_approvals
                (action_type, user_id, bank_code, section, payload, description, status,
                 merchant_initiated, merchant_name, merchant_email,
                 required_approvals, received_approvals, version, created_at, updated_at)
                VALUES ('settlement_config', :userId, :bankCode, 'account', :payload::jsonb, :description, 'pending',
                        true, :merchantName, :merchantEmail,
                        1, 0, 0, NOW(), NOW())
                RETURNING id
                """;

        try {
            Object result = entityManager.createNativeQuery(sql).setParameter("userId", merchantId)
                    .setParameter("bankCode", bankCode).setParameter("payload", payloadJson)
                    .setParameter("description", description).setParameter("merchantName", merchantName)
                    .setParameter("merchantEmail", merchantEmail).getSingleResult();

            Long approvalId = ((Number) result).longValue();
            log.info("Created settlement account change request {} for merchant {} (bank {})", approvalId, merchantId,
                    bankCode);
            return approvalId;
        } catch (Exception e) {
            log.error("Failed to insert pending approval for merchant {}", merchantId, e);
            return null;
        }
    }
}

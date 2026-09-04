package com.tms.report.modules.dispute.service;

import com.tms.report.core.export.XlsxExporter;
import com.tms.report.core.filter.QueryFilterHelper;
import com.tms.report.core.security.MerchantScope;
import com.tms.report.modules.dispute.dto.CreateDisputeDto;
import com.tms.report.modules.dispute.dto.DisputeThreadDto;
import com.tms.report.modules.dispute.model.Conversation;
import com.tms.report.modules.dispute.model.Dispute;
import com.tms.report.modules.dispute.repository.ConversationRepository;
import com.tms.report.modules.dispute.repository.DisputeRepository;
import com.tms.report.modules.grpc.service.GrpcClient;
import com.tms.report.modules.transaction.model.Transaction;
import com.tms.report.modules.transaction.repository.TransactionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DisputeService {

    /**
     * Sentinel for "user has never opened the thread", so every existing message
     * counts as unread.
     */
    private static final String NEVER_READ = "1970-01-01 00:00:00";

    private final GrpcClient grpcClient;
    private final MerchantScope merchantScope;
    private final DisputeRepository disputeRepository;
    private final ConversationRepository conversationRepository;
    private final TransactionRepository transactionRepository;
    private final EntityManager entityManager;

    public Map<String, Object> create(CreateDisputeDto dto) {
        Long merchantId = merchantScope.merchantId();
        if (merchantId == null) {
            throw new IllegalStateException("No authenticated merchant");
        }
        return grpcClient.createDispute(merchantId, dto.getTransactionReference(), dto.getSubject(), dto.getMessage());
    }

    public Map<String, Object> addConversation(Long disputeId, String message) {
        Long merchantId = merchantScope.merchantId();
        if (merchantId == null) {
            throw new IllegalStateException("No authenticated merchant");
        }
        // Verify the dispute belongs to this merchant
        if (disputeRepository.findByIdAndUserId(disputeId, merchantId).isEmpty()) {
            return Map.of("success", false, "message", "Dispute not found.");
        }
        // Use the REST endpoint which sets sender_type = 'user' for merchant messages.
        // The gRPC endpoint is for admin/agent messages from tms-report-java.
        return grpcClient.addUserDisputeConversation(disputeId, merchantId, message);
    }

    public Map<String, Object> close(Long disputeId) {
        Long merchantId = merchantScope.merchantId();
        if (merchantId == null) {
            throw new IllegalStateException("No authenticated merchant");
        }
        if (disputeRepository.findByIdAndUserId(disputeId, merchantId).isEmpty()) {
            return Map.of("success", false, "message", "Dispute not found.");
        }
        return grpcClient.closeDispute(disputeId);
    }

    /**
     * Fetch inbox threads for the current merchant, newest activity first.
     *
     * @param merchantUserId
     *            the logged-in merchant user's ID for unread tracking
     * @param filter
     *            optional status filter or "unread" for unread-only
     * @param search
     *            optional search term
     */
    public List<DisputeThreadDto> threads(Long merchantUserId, String filter, String search, int limit, int offset) {
        Long merchantId = merchantScope.merchantId();
        if (merchantId == null) {
            return List.of();
        }

        Map<String, Object> params = new LinkedHashMap<>();
        StringBuilder where = new StringBuilder(" WHERE d.user_id = :merchantId ");
        params.put("merchantId", merchantId);

        boolean unreadOnly = "unread".equalsIgnoreCase(filter);

        // Status filter (open, closed, etc.)
        if (filter != null && !filter.isBlank() && !"all".equalsIgnoreCase(filter) && !unreadOnly) {
            where.append(" AND d.status_code = :status ");
            params.put("status", filter);
        }

        if (search != null && !search.isBlank()) {
            String trimmed = search.trim();
            // If search is purely numeric, also match by dispute ID
            if (trimmed.matches("\\d+")) {
                where.append(" AND (d.id = :exactId OR d.subject ILIKE :q OR d.transaction_reference ILIKE :q) ");
                params.put("exactId", Long.parseLong(trimmed));
            } else {
                where.append(" AND (d.subject ILIKE :q OR d.transaction_reference ILIKE :q) ");
            }
            params.put("q", "%" + trimmed + "%");
        }

        // Filter to only unread if requested
        if (unreadOnly) {
            where.append(" AND COALESCE(uc.cnt, 0) > 0 ");
        }

        String sql = "SELECT d.id, d.subject, d.status_code, "
                + "  lm.message, lm.sender_type, lm.created_at AS last_message_at, lm.attachment_url, "
                + "  COALESCE(uc.cnt, 0) AS unread_count, d.created_at " + "FROM disputes d "
                + "LEFT JOIN merchant.dispute_reads dr ON dr.dispute_id = d.id AND dr.merchant_user_id = :muId "
                // Last message in the thread
                + "LEFT JOIN LATERAL ( " + "  SELECT c.message, c.sender_type, c.created_at, c.attachment_url "
                + "  FROM conversations c WHERE c.dispute_id = d.id "
                + "  ORDER BY c.created_at DESC, c.id DESC LIMIT 1 " + ") lm ON TRUE "
                // Inbound messages (from admin/agent) this merchant user hasn't seen
                + "LEFT JOIN LATERAL ( " + "  SELECT COUNT(*) AS cnt FROM conversations c2 "
                + "  WHERE c2.dispute_id = d.id AND c2.sender_type IN ('admin', 'agent') "
                + "    AND c2.created_at > COALESCE(dr.last_read_at, TIMESTAMP '" + NEVER_READ + "') " + ") uc ON TRUE "
                + where + " ORDER BY COALESCE(lm.created_at, d.created_at) DESC LIMIT :limit OFFSET :offset";

        Query q = entityManager.createNativeQuery(sql);
        q.setParameter("muId", merchantUserId != null ? merchantUserId : 0L);
        q.setParameter("limit", Math.max(1, Math.min(limit, 200)));
        q.setParameter("offset", Math.max(0, offset));
        params.forEach(q::setParameter);

        List<DisputeThreadDto> out = new ArrayList<>();
        for (Object row : q.getResultList()) {
            Object[] r = (Object[]) row;
            String attachmentKey = str(r[6]);
            out.add(new DisputeThreadDto(lng(r[0]), // id
                    str(r[1]), // reason (subject)
                    str(r[2]), // status_code
                    str(r[3]), // last_message
                    normalizeSender(str(r[4])), // last_message_sender
                    str(r[5]), // last_message_at
                    attachmentKey != null, // last_message_has_attachment
                    r[7] != null ? ((Number) r[7]).longValue() : 0L, // unread_count
                    str(r[8]) // created_at
            ));
        }
        return out;
    }

    /**
     * Total unread admin/agent messages across all disputes for this merchant user.
     */
    public long totalUnread(Long merchantUserId) {
        Long merchantId = merchantScope.merchantId();
        if (merchantId == null || merchantUserId == null) {
            return 0L;
        }

        String sql = "SELECT COUNT(*) FROM conversations c " + "JOIN disputes d ON d.id = c.dispute_id "
                + "LEFT JOIN merchant.dispute_reads dr ON dr.dispute_id = d.id AND dr.merchant_user_id = :muId "
                + "WHERE d.user_id = :merchantId AND c.sender_type IN ('admin', 'agent') "
                + "  AND c.created_at > COALESCE(dr.last_read_at, TIMESTAMP '" + NEVER_READ + "')";

        Query q = entityManager.createNativeQuery(sql);
        q.setParameter("muId", merchantUserId);
        q.setParameter("merchantId", merchantId);
        Object result = q.getSingleResult();
        return result != null ? ((Number) result).longValue() : 0L;
    }

    /**
     * Mark a thread read up to now for this merchant user.
     */
    @Transactional
    public void markRead(Long merchantUserId, Long disputeId) {
        if (merchantUserId == null || disputeId == null) {
            return;
        }
        entityManager.createNativeQuery("INSERT INTO merchant.dispute_reads "
                + "(merchant_user_id, dispute_id, last_read_at, created_at, updated_at) "
                + "VALUES (:muId, :disputeId, NOW(), NOW(), NOW()) " + "ON CONFLICT (merchant_user_id, dispute_id) "
                + "DO UPDATE SET last_read_at = NOW(), updated_at = NOW()").setParameter("muId", merchantUserId)
                .setParameter("disputeId", disputeId).executeUpdate();
    }

    private static String normalizeSender(String senderType) {
        if (senderType == null || senderType.isBlank()) {
            return null;
        }
        return "agent".equals(senderType) ? "admin" : senderType;
    }

    public Page<Map<String, Object>> index(Map<String, String> params) {
        Long merchantId = merchantScope.merchantId();
        if (merchantId == null) {
            return Page.empty();
        }

        int page = Integer.parseInt(params.getOrDefault("page", "1")) - 1;
        int limit = Integer.parseInt(params.getOrDefault("limit", "15"));

        Map<String, Object> qParams = new HashMap<>();
        StringBuilder where = new StringBuilder("WHERE d.user_id = :merchantId");
        qParams.put("merchantId", merchantId);

        // Apply status filter
        String status = trimToNull(params.get("status"));
        if (status != null) {
            where.append(" AND d.status_code = :status");
            qParams.put("status", status);
        }

        // Apply search filter (ID, subject, reference)
        String search = trimToNull(params.get("search"));
        if (search != null) {
            if (search.matches("\\d+")) {
                where.append(
                        " AND (d.id = :searchId OR LOWER(d.subject) ILIKE :search OR LOWER(d.transaction_reference) ILIKE :search)");
                qParams.put("searchId", Long.parseLong(search));
            } else {
                where.append(" AND (LOWER(d.subject) ILIKE :search OR LOWER(d.transaction_reference) ILIKE :search)");
            }
            qParams.put("search", "%" + search.toLowerCase() + "%");
        }

        // Apply date range using QueryFilterHelper
        QueryFilterHelper.applyDates(where, qParams, params, "d.created_at");

        String sql = "SELECT * FROM disputes d " + where + " ORDER BY d.created_at DESC";
        String countSql = "SELECT COUNT(*) FROM disputes d " + where;

        Query countQ = entityManager.createNativeQuery(countSql);
        qParams.forEach(countQ::setParameter);
        long total = ((Number) countQ.getSingleResult()).longValue();

        Query q = entityManager.createNativeQuery(sql, Dispute.class);
        qParams.forEach(q::setParameter);
        q.setFirstResult(page * limit);
        q.setMaxResults(limit);

        @SuppressWarnings("unchecked")
        List<Dispute> disputes = q.getResultList();

        return new PageImpl<>(disputes.stream().map(this::toView).toList(), PageRequest.of(page, limit), total);
    }

    public Map<String, Object> filters() {
        return Map.of("statuses",
                List.of(Map.of("code", "open", "label", "Open"), Map.of("code", "processing", "label", "Processing"),
                        Map.of("code", "resolved", "label", "Resolved"), Map.of("code", "closed", "label", "Closed")));
    }

    public void export(Map<String, String> params, jakarta.servlet.http.HttpServletResponse response) throws Exception {
        Long merchantId = merchantScope.merchantId();
        if (merchantId == null) {
            throw new IllegalStateException("No authenticated merchant");
        }

        Map<String, Object> qParams = new HashMap<>();
        StringBuilder where = new StringBuilder("WHERE d.user_id = :merchantId");
        qParams.put("merchantId", merchantId);

        // Apply status filter
        String status = trimToNull(params.get("status"));
        if (status != null) {
            where.append(" AND d.status_code = :status");
            qParams.put("status", status);
        }

        // Apply search filter (ID, subject, reference)
        String search = trimToNull(params.get("search"));
        if (search != null) {
            if (search.matches("\\d+")) {
                where.append(
                        " AND (d.id = :searchId OR LOWER(d.subject) ILIKE :search OR LOWER(d.transaction_reference) ILIKE :search)");
                qParams.put("searchId", Long.parseLong(search));
            } else {
                where.append(" AND (LOWER(d.subject) ILIKE :search OR LOWER(d.transaction_reference) ILIKE :search)");
            }
            qParams.put("search", "%" + search.toLowerCase() + "%");
        }

        // Apply date range using QueryFilterHelper
        QueryFilterHelper.applyDates(where, qParams, params, "d.created_at");

        String sql = "SELECT * FROM disputes d " + where + " ORDER BY d.created_at DESC";

        String[] headers = {"ID", "Subject", "Transaction Reference", "Status", "Created At", "Resolved At"};
        String[] keys = {"id", "subject", "transaction_reference", "status_code", "created_at", "resolved_at"};

        XlsxExporter.streamPaged(response, "disputes", headers, 500, (page, size) -> {
            Query q = entityManager.createNativeQuery(sql, Dispute.class);
            qParams.forEach(q::setParameter);
            q.setFirstResult(page * size);
            q.setMaxResults(size);
            @SuppressWarnings("unchecked")
            List<Dispute> disputes = q.getResultList();

            return disputes.stream().map(d -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", d.getId());
                m.put("subject", d.getSubject());
                m.put("transaction_reference", d.getTransactionReference());
                m.put("status_code", d.getStatusCode());
                m.put("created_at", d.getCreatedAt() != null ? d.getCreatedAt().toString() : "");
                m.put("resolved_at", d.getResolvedAt() != null ? d.getResolvedAt().toString() : "");
                return m;
            }).toList();
        }, row -> {
            String[] vals = new String[keys.length];
            for (int i = 0; i < keys.length; i++) {
                Object v = row.get(keys[i]);
                vals[i] = v != null ? v.toString() : "";
            }
            return vals;
        });
    }

    public Map<String, Object> show(Long id) {
        Long merchantId = merchantScope.merchantId();
        if (merchantId == null) {
            return null;
        }

        Dispute dispute = disputeRepository.findByIdAndUserId(id, merchantId).orElse(null);
        if (dispute == null) {
            return null;
        }

        Map<String, Object> view = toView(dispute);

        // Fetch transaction details if transaction_reference is present
        String txnRef = dispute.getTransactionReference();
        if (txnRef != null && !txnRef.isBlank()) {
            transactionRepository.findByReference(txnRef).ifPresent(txn -> {
                view.put("transaction", mapTransaction(txn));
            });
        }

        // Fetch conversations separately to avoid lazy loading issues
        List<Conversation> conversations = conversationRepository.findByDisputeIdOrderByCreatedAtAsc(id);
        view.put("conversations", conversations.stream().map(this::conversationToView).toList());

        return view;
    }

    private Map<String, Object> toView(Dispute d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("transaction_reference", d.getTransactionReference());
        m.put("reason", d.getSubject());
        m.put("subject", d.getSubject());
        m.put("message", d.getMessage());
        m.put("status_code", d.getStatusCode());
        m.put("status", Map.of("code", d.getStatusCode() != null ? d.getStatusCode() : "", "description",
                d.getStatusDescription() != null ? d.getStatusDescription() : ""));
        m.put("attachment_url", d.getAttachmentUrl());
        m.put("resolved_at", d.getResolvedAt() != null ? d.getResolvedAt().toString() : null);
        m.put("created_at", d.getCreatedAt() != null ? d.getCreatedAt().toString() : null);
        m.put("updated_at", d.getUpdatedAt() != null ? d.getUpdatedAt().toString() : null);
        return m;
    }

    private Map<String, Object> conversationToView(Conversation c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("user_id", c.getUserId());
        m.put("sender_type", c.getSenderType());
        m.put("sender_name", c.getSenderName());
        m.put("dispute_id", c.getDisputeId());
        m.put("message", c.getMessage());
        m.put("created_at", c.getCreatedAt() != null ? c.getCreatedAt().toString() : null);
        return m;
    }

    private static Long lng(Object o) {
        return o != null ? ((Number) o).longValue() : null;
    }

    private static String str(Object o) {
        return o != null ? o.toString().trim() : null;
    }

    private Map<String, Object> mapTransaction(Transaction txn) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", txn.getId());
        m.put("reference", txn.getReference());
        m.put("amount", txn.getAmount() != null ? txn.getAmount().toPlainString() : null);
        m.put("status",
                txn.getStatusCode() != null ? Map.of("code", txn.getStatusCode(), "name", txn.getStatusCode()) : null);
        m.put("product", txn.getProductId() != null ? getProductName(txn.getProductId()) : null);
        m.put("created_at", txn.getCreatedAt() != null ? txn.getCreatedAt().toString() : null);
        return m;
    }

    private String getProductName(Long productId) {
        try {
            Object result = entityManager.createNativeQuery("SELECT name FROM products WHERE id = :id")
                    .setParameter("id", productId).getSingleResult();
            return result != null ? result.toString() : null;
        } catch (Exception e) {
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
}

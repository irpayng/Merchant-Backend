package com.tms.report.modules.dispute.service;

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
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DisputeService {

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
     */
    public List<DisputeThreadDto> threads(String filter, String search, int limit, int offset) {
        Long merchantId = merchantScope.merchantId();
        if (merchantId == null) {
            return List.of();
        }

        Map<String, Object> params = new LinkedHashMap<>();
        StringBuilder where = new StringBuilder(" WHERE d.user_id = :merchantId ");
        params.put("merchantId", merchantId);

        // Status filter (open, closed, etc.)
        if (filter != null && !filter.isBlank() && !"all".equalsIgnoreCase(filter)
                && !"unread".equalsIgnoreCase(filter)) {
            where.append(" AND d.status_code = :status ");
            params.put("status", filter);
        }

        if (search != null && !search.isBlank()) {
            where.append(" AND (d.subject ILIKE :q OR d.transaction_reference ILIKE :q) ");
            params.put("q", "%" + search.trim() + "%");
        }

        // Simplified query without unread tracking (merchants see their own disputes)
        String sql = "SELECT d.id, d.subject, d.status_code, "
                + "  lm.message, lm.sender_type, lm.created_at AS last_message_at, lm.attachment_url, "
                + "  d.created_at " + "FROM disputes d "
                // Last message in the thread
                + "LEFT JOIN LATERAL ( " + "  SELECT c.message, c.sender_type, c.created_at, c.attachment_url "
                + "  FROM conversations c WHERE c.dispute_id = d.id "
                + "  ORDER BY c.created_at DESC, c.id DESC LIMIT 1 " + ") lm ON TRUE " + where
                + " ORDER BY COALESCE(lm.created_at, d.created_at) DESC LIMIT :limit OFFSET :offset";

        Query q = entityManager.createNativeQuery(sql);
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
                    0L, // unread_count (not tracked for merchant portal)
                    str(r[7]) // created_at
            ));
        }
        return out;
    }

    /**
     * Total unread admin messages across all disputes for this merchant. Returns 0
     * since read-state tracking is not implemented for the merchant portal.
     */
    public long totalUnread() {
        return 0L;
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

        int page = Integer.parseInt(params.getOrDefault("page", "1"));
        int limit = Integer.parseInt(params.getOrDefault("limit", "15"));
        String search = params.get("search");
        String status = params.get("status");

        PageRequest pageable = PageRequest.of(Math.max(page - 1, 0), limit, Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<Dispute> disputes;
        if (search != null && !search.isBlank()) {
            if (status != null && !status.isBlank()) {
                disputes = disputeRepository.searchByUserIdAndStatus(merchantId, search.trim(), status, pageable);
            } else {
                disputes = disputeRepository.searchByUserId(merchantId, search.trim(), pageable);
            }
        } else if (status != null && !status.isBlank()) {
            disputes = disputeRepository.findByUserIdAndStatusCodeOrderByCreatedAtDesc(merchantId, status, pageable);
        } else {
            disputes = disputeRepository.findByUserIdOrderByCreatedAtDesc(merchantId, pageable);
        }

        return disputes.map(this::toView);
    }

    public Map<String, Object> filters() {
        return Map.of("statuses",
                List.of(Map.of("code", "open", "label", "Open"),
                        Map.of("code", "processing", "label", "Processing"),
                        Map.of("code", "resolved", "label", "Resolved"),
                        Map.of("code", "closed", "label", "Closed")));
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
}

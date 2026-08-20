package com.tms.report.modules.dispute.service;

import com.tms.report.core.security.MerchantScope;
import com.tms.report.modules.dispute.dto.CreateDisputeDto;
import com.tms.report.modules.dispute.dto.DisputeThreadDto;
import com.tms.report.modules.dispute.model.Conversation;
import com.tms.report.modules.dispute.model.Dispute;
import com.tms.report.modules.dispute.repository.ConversationRepository;
import com.tms.report.modules.dispute.repository.DisputeRepository;
import com.tms.report.modules.grpc.service.GrpcClient;
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
        return grpcClient.addDisputeConversation(disputeId, message);
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

        boolean unreadOnly = "unread".equalsIgnoreCase(filter);
        if (filter != null && !filter.isBlank() && !unreadOnly && !"all".equalsIgnoreCase(filter)) {
            where.append(" AND d.status_code = :status ");
            params.put("status", filter);
        }

        if (search != null && !search.isBlank()) {
            where.append(" AND (d.subject ILIKE :q OR d.transaction_reference ILIKE :q) ");
            params.put("q", "%" + search.trim() + "%");
        }

        // For unread filter, we need to check if there are admin messages the user
        // hasn't seen
        if (unreadOnly) {
            where.append(" AND COALESCE(uc.cnt, 0) > 0 ");
        }

        String sql = "SELECT d.id, d.subject, d.status_code, "
                + "  lm.message, lm.sender_type, lm.created_at AS last_message_at, lm.attachment_url, "
                + "  COALESCE(uc.cnt, 0) AS unread_count, d.created_at " + "FROM disputes d "
                // Last message in the thread
                + "LEFT JOIN LATERAL ( " + "  SELECT c.message, c.sender_type, c.created_at, c.attachment_url "
                + "  FROM conversations c WHERE c.dispute_id = d.id "
                + "  ORDER BY c.created_at DESC, c.id DESC LIMIT 1 " + ") lm ON TRUE "
                // Unread admin messages (messages from support the merchant hasn't seen)
                + "LEFT JOIN LATERAL ( " + "  SELECT COUNT(*) AS cnt FROM conversations c2 "
                + "  WHERE c2.dispute_id = d.id AND c2.sender_type IN ('admin', 'agent') "
                + "    AND c2.created_at > COALESCE(d.last_read_at, TIMESTAMP '1970-01-01 00:00:00') " + ") uc ON TRUE "
                + where + " ORDER BY COALESCE(lm.created_at, d.created_at) DESC LIMIT :limit OFFSET :offset";

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
                    r[7] != null ? ((Number) r[7]).longValue() : 0L, // unread_count
                    str(r[8]) // created_at
            ));
        }
        return out;
    }

    /**
     * Total unread admin messages across all disputes for this merchant.
     */
    public long totalUnread() {
        Long merchantId = merchantScope.merchantId();
        if (merchantId == null) {
            return 0L;
        }

        Query q = entityManager
                .createNativeQuery("SELECT COUNT(*) FROM conversations c " + "JOIN disputes d ON d.id = c.dispute_id "
                        + "WHERE d.user_id = :merchantId " + "  AND c.sender_type IN ('admin', 'agent') "
                        + "  AND c.created_at > COALESCE(d.last_read_at, TIMESTAMP '1970-01-01 00:00:00')");
        q.setParameter("merchantId", merchantId);
        Object result = q.getSingleResult();
        return result != null ? ((Number) result).longValue() : 0L;
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

        PageRequest pageable = PageRequest.of(Math.max(page - 1, 0), limit, Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<Dispute> disputes;
        if (search != null && !search.isBlank()) {
            disputes = disputeRepository.searchByUserId(merchantId, search.trim(), pageable);
        } else {
            disputes = disputeRepository.findByUserIdOrderByCreatedAtDesc(merchantId, pageable);
        }

        return disputes.map(this::toView);
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
}

package com.tms.report.modules.dispute.service;

import com.tms.report.core.security.MerchantScope;
import com.tms.report.modules.dispute.dto.CreateDisputeDto;
import com.tms.report.modules.dispute.model.Conversation;
import com.tms.report.modules.dispute.model.Dispute;
import com.tms.report.modules.dispute.repository.DisputeRepository;
import com.tms.report.modules.grpc.service.GrpcClient;
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

        // Include conversations
        List<Conversation> conversations = dispute.getConversations();
        if (conversations != null) {
            view.put("conversations", conversations.stream().map(this::conversationToView).toList());
        } else {
            view.put("conversations", List.of());
        }

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
        m.put("status", Map.of(
                "code", d.getStatusCode() != null ? d.getStatusCode() : "",
                "description", d.getStatusDescription() != null ? d.getStatusDescription() : ""));
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
}

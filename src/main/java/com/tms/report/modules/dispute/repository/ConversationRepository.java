package com.tms.report.modules.dispute.repository;

import com.tms.report.modules.dispute.model.Conversation;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    List<Conversation> findByDisputeIdOrderByCreatedAtAsc(Long disputeId);
}

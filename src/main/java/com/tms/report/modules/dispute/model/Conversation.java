package com.tms.report.modules.dispute.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "conversations")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dispute_id", nullable = false)
    private Long disputeId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "sender_type", nullable = false)
    private String senderType;

    @Column(name = "sender_name")
    private String senderName;

    @Column(nullable = false, columnDefinition = "text")
    private String message;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}

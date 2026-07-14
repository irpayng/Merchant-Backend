package com.tms.report.modules.dispute.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "disputes")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Dispute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "transaction_reference")
    private String transactionReference;

    @Column(nullable = false)
    private String subject;

    @Column(columnDefinition = "text")
    private String message;

    @Column(name = "status_code", nullable = false)
    private String statusCode;

    @Column(name = "status_description")
    private String statusDescription;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "attachment_url")
    private String attachmentUrl;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @OneToMany(fetch = FetchType.LAZY)
    @JoinColumn(name = "dispute_id", insertable = false, updatable = false)
    @OrderBy("createdAt ASC")
    private List<Conversation> conversations;
}

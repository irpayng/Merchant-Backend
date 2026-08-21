package com.tms.report.modules.user.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "users")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String email;

    @Column(name = "phone_number")
    private String phoneNumber;

    @JsonIgnore
    private String password;

    @Column(name = "account_number", length = 10)
    private String accountNumber;

    @Column(name = "fcm_token")
    @JsonIgnore
    private String fcmToken;

    private String type;

    @Column(name = "tier_id")
    private Long tierId;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "onboarding_id")
    private Long onboardingId;

    @Column(name = "email_verified_at")
    private LocalDateTime emailVerifiedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "bvn_photo_url")
    private String bvnPhotoUrl;

    @Column(name = "selfie_url")
    private String selfieUrl;

    /**
     * Registered business name, stamped on the user when a merchant business
     * application is approved. Used as the display name of the dashboard owner
     * login provisioned for that merchant.
     */
    @Column(name = "business_name")
    private String businessName;

    @Transient
    private Boolean isActive;

    @Transient
    private LocalDateTime lastTransactionDate;

    @Transient
    private Boolean pnd;

    @Transient
    private String name;

    public String getName() {
        if (name != null)
            return name;
        return email;
    }
}

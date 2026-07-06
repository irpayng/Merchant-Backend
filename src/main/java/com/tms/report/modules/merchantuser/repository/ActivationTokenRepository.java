package com.tms.report.modules.merchantuser.repository;

import com.tms.report.modules.merchantuser.model.ActivationToken;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActivationTokenRepository extends JpaRepository<ActivationToken, Long> {

    Optional<ActivationToken> findByToken(String token);

    Optional<ActivationToken> findTopByMerchantUserIdAndOtpOrderByCreatedAtDesc(Long merchantUserId, String otp);

    void deleteByMerchantUserId(Long merchantUserId);
}

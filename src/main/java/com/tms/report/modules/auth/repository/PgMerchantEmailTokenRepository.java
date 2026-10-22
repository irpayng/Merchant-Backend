package com.tms.report.modules.auth.repository;

import com.tms.report.modules.auth.model.PgMerchantEmailToken;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PgMerchantEmailTokenRepository extends JpaRepository<PgMerchantEmailToken, Long> {

    Optional<PgMerchantEmailToken> findTopByEmailOrderByCreatedAtDesc(String email);

    Optional<PgMerchantEmailToken> findTopByEmailAndOtpOrderByCreatedAtDesc(String email, String otp);

    void deleteByEmail(String email);

    void deleteByUserId(Long userId);
}

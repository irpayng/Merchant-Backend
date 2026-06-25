package com.tms.report.modules.auth.repository;

import com.tms.report.modules.auth.model.PasswordReset;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PasswordResetRepository extends JpaRepository<PasswordReset, Long> {

    Optional<PasswordReset> findByToken(String token);

    Optional<PasswordReset> findByEmailAndToken(String email, String token);

    Optional<PasswordReset> findTopByEmailOrderByCreatedAtDesc(String email);

    void deleteByEmail(String email);
}

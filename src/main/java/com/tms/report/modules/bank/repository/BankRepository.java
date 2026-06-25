package com.tms.report.modules.bank.repository;

import com.tms.report.modules.bank.model.Bank;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BankRepository extends JpaRepository<Bank, Long> {
    Optional<Bank> findByCode(String code);

    boolean existsByCode(String code);
}

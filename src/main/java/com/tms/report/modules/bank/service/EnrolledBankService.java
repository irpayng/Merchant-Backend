package com.tms.report.modules.bank.service;

import com.tms.report.core.exception.AppException;
import com.tms.report.modules.bank.model.Bank;
import com.tms.report.modules.bank.repository.BankRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.NoResultException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Enrolled tenant-bank registry for the super-merchant portal. */
@Service
@RequiredArgsConstructor
public class EnrolledBankService {

    private final BankRepository bankRepository;
    private final EntityManager entityManager;

    @Transactional(readOnly = true)
    public List<Bank> list() {
        return bankRepository.findAll(Sort.by(Sort.Direction.ASC, "name"));
    }

    @Transactional
    public Bank enroll(String code, String name, String contactEmail) {
        if (code == null || code.isBlank()) {
            throw new AppException("Bank code is required.", HttpStatus.BAD_REQUEST);
        }
        String c = code.trim();
        if (bankRepository.existsByCode(c)) {
            throw new AppException("This bank is already enrolled.", HttpStatus.CONFLICT);
        }
        // Validate the code against the NIBSS bank_codes reference and default the
        // display name from it when not supplied.
        String referenceName = lookupBankName(c);
        if (referenceName == null) {
            throw new AppException("Unknown NIBSS bank code: " + c, HttpStatus.BAD_REQUEST);
        }
        String resolvedName = (name != null && !name.isBlank()) ? name.trim() : referenceName;
        Bank bank = Bank.builder().code(c).name(resolvedName)
                .contactEmail(contactEmail != null && !contactEmail.isBlank() ? contactEmail.trim() : null)
                .status("active").build();
        return bankRepository.save(bank);
    }

    @Transactional
    public Bank updateStatus(Long id, String status) {
        Bank bank = bankRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Bank not found"));
        bank.setStatus("inactive".equalsIgnoreCase(status) ? "inactive" : "active");
        return bankRepository.save(bank);
    }

    private String lookupBankName(String code) {
        try {
            Object name = entityManager.createNativeQuery("SELECT name FROM bank_codes WHERE code = :c")
                    .setParameter("c", code).getSingleResult();
            return name != null ? name.toString() : code;
        } catch (NoResultException e) {
            return null;
        }
    }
}

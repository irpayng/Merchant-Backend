package com.tms.report.modules.dispute.repository;

import com.tms.report.modules.dispute.model.Dispute;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DisputeRepository extends JpaRepository<Dispute, Long> {

    Page<Dispute> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    @Query("SELECT d FROM Dispute d WHERE d.userId = :userId AND " +
           "(LOWER(d.subject) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "LOWER(d.transactionReference) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<Dispute> searchByUserId(@Param("userId") Long userId, @Param("search") String search, Pageable pageable);

    Optional<Dispute> findByIdAndUserId(Long id, Long userId);
}

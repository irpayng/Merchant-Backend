package com.tms.report.modules.admin.repository;

import com.tms.report.modules.admin.model.Admin;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface AdminRepository extends JpaRepository<Admin, Long>, JpaSpecificationExecutor<Admin> {

    Optional<Admin> findByEmail(String email);

    Optional<Admin> findByPhoneNumber(String phoneNumber);

    boolean existsByEmail(String email);

    @Query("SELECT a FROM Admin a WHERE " + "LOWER(a.name) LIKE LOWER(CONCAT('%', :search, '%')) OR "
            + "LOWER(a.email) LIKE LOWER(CONCAT('%', :search, '%'))")
    Page<Admin> searchByNameOrEmail(String search, Pageable pageable);
}

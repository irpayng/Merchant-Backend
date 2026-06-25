package com.tms.report.modules.privilege.repository;

import com.tms.report.modules.privilege.model.Privilege;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PrivilegeRepository extends JpaRepository<Privilege, Long> {

    Optional<Privilege> findByCode(String code);
}

package com.tms.report.modules.role.repository;

import com.tms.report.modules.role.model.Privilege;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PrivilegeRepository extends JpaRepository<Privilege, Long> {

    Optional<Privilege> findByCode(String code);

    List<Privilege> findByCodeIn(Set<String> codes);

    List<Privilege> findByModule(String module);

    boolean existsByCode(String code);
}

package com.tms.report.modules.role.repository;

import com.tms.report.modules.role.model.Role;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, Long> {

    List<Role> findByMerchantId(Long merchantId);

    Optional<Role> findByMerchantIdAndSlug(Long merchantId, String slug);

    Optional<Role> findByMerchantIdAndId(Long merchantId, Long id);

    boolean existsByMerchantIdAndSlug(Long merchantId, String slug);
}

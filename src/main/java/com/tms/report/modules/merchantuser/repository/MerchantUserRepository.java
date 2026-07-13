package com.tms.report.modules.merchantuser.repository;

import com.tms.report.modules.merchantuser.model.MerchantUser;
import com.tms.report.modules.role.model.Role;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface MerchantUserRepository
        extends
            JpaRepository<MerchantUser, Long>,
            JpaSpecificationExecutor<MerchantUser> {

    Optional<MerchantUser> findByEmail(String email);

    Optional<MerchantUser> findByPhoneNumber(String phoneNumber);

    /**
     * All logins (owner + cashiers) for a merchant — powers the owner's staff list.
     */
    List<MerchantUser> findByMerchantId(Long merchantId);

    /**
     * Users assigned to a given role — used to prevent role deletion with active
     * assignments.
     */
    List<MerchantUser> findByRoleEntity(Role roleEntity);
}

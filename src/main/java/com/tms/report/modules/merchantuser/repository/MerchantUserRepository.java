package com.tms.report.modules.merchantuser.repository;

import com.tms.report.modules.merchantuser.model.MerchantUser;
import com.tms.report.modules.role.model.Role;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MerchantUserRepository
        extends
            JpaRepository<MerchantUser, Long>,
            JpaSpecificationExecutor<MerchantUser> {

    Optional<MerchantUser> findByEmail(String email);

    Optional<MerchantUser> findByPhoneNumber(String phoneNumber);

    /**
     * Find a staff user by their tms-user operator id.
     */
    Optional<MerchantUser> findByOperatorId(Long operatorId);

    /**
     * All logins (owner + cashiers) for a merchant — powers the owner's staff list.
     */
    List<MerchantUser> findByMerchantId(Long merchantId);

    /**
     * Find the owner record for a merchant by their tms-user id. Used for JWT
     * validation where the subject is {@code "u:<merchantId>"}.
     */
    @Query("SELECT u FROM MerchantUser u WHERE u.merchantId = :merchantId AND u.operatorId IS NULL")
    Optional<MerchantUser> findOwnerByMerchantId(@Param("merchantId") Long merchantId);

    /**
     * Users assigned to a given role — used to prevent role deletion with active
     * assignments.
     */
    List<MerchantUser> findByRoleEntity(Role roleEntity);
}

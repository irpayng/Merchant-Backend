package com.tms.report.modules.user.repository;

import com.tms.report.modules.user.model.User;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

    Optional<User> findByEmail(String email);

    Optional<User> findByPhoneNumber(String phoneNumber);

    @Query("SELECT COUNT(DISTINCT u.id) FROM User u")
    long countTotal();

    @Query(value = "SELECT COUNT(DISTINCT u.id) FROM users u " + "INNER JOIN transactions t ON t.user_id = u.id "
            + "WHERE t.created_at >= :since", nativeQuery = true)
    long countActiveSince(@Param("since") LocalDateTime since);

    default long countActive() {
        return countActiveSince(LocalDateTime.now().minusDays(7));
    }
}

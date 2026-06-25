package com.tms.report.modules.notification.repository;

import com.tms.report.modules.notification.model.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface NotificationRepository
        extends
            JpaRepository<Notification, Long>,
            JpaSpecificationExecutor<Notification> {

    @Modifying
    @Transactional
    @Query("UPDATE Notification n SET n.readAt = CURRENT_TIMESTAMP WHERE n.readAt IS NULL")
    void markAllAsRead();
}

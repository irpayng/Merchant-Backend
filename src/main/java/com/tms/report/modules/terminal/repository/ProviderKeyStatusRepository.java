package com.tms.report.modules.terminal.repository;

import com.tms.report.modules.terminal.model.ProviderKeyStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ProviderKeyStatusRepository extends JpaRepository<ProviderKeyStatus, Long> {

    ProviderKeyStatus findByTerminalId(String terminalId);

    List<ProviderKeyStatus> findByKeyStatus(String keyStatus);

    @Query("SELECT COUNT(p) FROM ProviderKeyStatus p WHERE p.keyStatus = 'ready'")
    long countReady();

    @Query("SELECT COUNT(p) FROM ProviderKeyStatus p WHERE p.keyStatus = 'failed'")
    long countFailed();

    @Query("SELECT COUNT(p) FROM ProviderKeyStatus p WHERE p.keyStatus = 'pending'")
    long countPending();
}

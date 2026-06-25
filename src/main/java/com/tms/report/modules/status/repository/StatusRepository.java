package com.tms.report.modules.status.repository;

import com.tms.report.modules.status.model.Status;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StatusRepository extends JpaRepository<Status, Long> {

    List<Status> findByContext(String context);

    List<Status> findByCodeIn(List<String> codes);
}

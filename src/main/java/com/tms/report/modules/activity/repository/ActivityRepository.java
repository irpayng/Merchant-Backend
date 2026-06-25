package com.tms.report.modules.activity.repository;

import com.tms.report.modules.activity.model.Activity;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActivityRepository extends JpaRepository<Activity, Long> {

    Page<Activity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<Activity> findTop5ByOrderByCreatedAtDesc();
}

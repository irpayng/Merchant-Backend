package com.tms.report.modules.tid.repository;

import com.tms.report.modules.tid.model.Tid;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface TidRepository extends JpaRepository<Tid, Long>, JpaSpecificationExecutor<Tid> {
}

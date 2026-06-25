package com.tms.report.modules.upload.repository;

import com.tms.report.modules.upload.model.Upload;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface UploadRepository extends JpaRepository<Upload, Long>, JpaSpecificationExecutor<Upload> {
}

package com.tms.report.modules.otp.repository;

import com.tms.report.modules.otp.model.Otp;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface OtpRepository extends JpaRepository<Otp, Long>, JpaSpecificationExecutor<Otp> {
}

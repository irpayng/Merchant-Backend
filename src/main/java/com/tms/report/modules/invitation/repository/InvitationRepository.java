package com.tms.report.modules.invitation.repository;

import com.tms.report.modules.invitation.model.Invitation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvitationRepository extends JpaRepository<Invitation, Long> {
}

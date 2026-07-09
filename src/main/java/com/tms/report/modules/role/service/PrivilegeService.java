package com.tms.report.modules.role.service;

import com.tms.report.modules.role.model.Privilege;
import com.tms.report.modules.role.repository.PrivilegeRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Read-only service for privileges. Privileges are system-defined and seeded
 * on startup — merchants cannot create or delete them, only assign them to
 * roles.
 */
@Service
@RequiredArgsConstructor
public class PrivilegeService {

    private final PrivilegeRepository privilegeRepository;

    public List<Privilege> listAll() {
        return privilegeRepository.findAll();
    }

    public List<Privilege> listByModule(String module) {
        return privilegeRepository.findByModule(module);
    }
}

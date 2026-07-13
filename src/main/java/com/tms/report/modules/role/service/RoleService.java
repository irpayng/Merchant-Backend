package com.tms.report.modules.role.service;

import com.tms.report.core.exception.AppException;
import com.tms.report.core.security.MerchantScope;
import com.tms.report.modules.merchantuser.model.MerchantUser;
import com.tms.report.modules.merchantuser.repository.MerchantUserRepository;
import com.tms.report.modules.role.model.Privilege;
import com.tms.report.modules.role.model.Role;
import com.tms.report.modules.role.repository.PrivilegeRepository;
import com.tms.report.modules.role.repository.RoleRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RoleService {

    private final RoleRepository roleRepository;
    private final PrivilegeRepository privilegeRepository;
    private final MerchantUserRepository merchantUserRepository;
    private final MerchantScope merchantScope;

    public List<Role> listRoles() {
        return roleRepository.findByMerchantId(merchantScope.merchantId());
    }

    public Role getRole(Long id) {
        return roleRepository.findByMerchantIdAndId(merchantScope.merchantId(), id)
                .orElseThrow(() -> new AppException("Role not found", HttpStatus.NOT_FOUND));
    }

    @Transactional
    public Role createRole(String name, String slug, String description, Set<Long> privilegeIds) {
        Long merchantId = merchantScope.merchantId();
        if (roleRepository.existsByMerchantIdAndSlug(merchantId, slug)) {
            throw new AppException("A role with this slug already exists", HttpStatus.CONFLICT);
        }

        Set<Privilege> privileges = new HashSet<>();
        if (privilegeIds != null && !privilegeIds.isEmpty()) {
            privileges.addAll(privilegeRepository.findAllById(privilegeIds));
        }

        Role role = Role.builder().merchantId(merchantId).name(name).slug(slug).description(description)
                .systemRole(false).privileges(privileges).build();

        return roleRepository.save(role);
    }

    @Transactional
    public Role updateRole(Long id, String name, String description, Set<Long> privilegeIds) {
        Role role = getRole(id);
        if (role.isSystemRole()) {
            throw new AppException("System roles cannot be renamed", HttpStatus.FORBIDDEN);
        }

        role.setName(name);
        if (description != null) {
            role.setDescription(description);
        }

        if (privilegeIds != null) {
            Set<Privilege> privileges = new HashSet<>(privilegeRepository.findAllById(privilegeIds));
            role.setPrivileges(privileges);
        }

        return roleRepository.save(role);
    }

    @Transactional
    public void deleteRole(Long id) {
        Role role = getRole(id);
        if (role.isSystemRole()) {
            throw new AppException("System roles cannot be deleted", HttpStatus.FORBIDDEN);
        }

        // Prevent deletion if users are still assigned
        List<MerchantUser> assigned = merchantUserRepository.findByRoleEntity(role);
        if (!assigned.isEmpty()) {
            throw new AppException("Cannot delete role — " + assigned.size() + " user(s) are still assigned to it",
                    HttpStatus.CONFLICT);
        }

        roleRepository.delete(role);
    }

    @Transactional
    public Role syncPrivileges(Long roleId, Set<Long> privilegeIds) {
        Role role = getRole(roleId);
        Set<Privilege> privileges = new HashSet<>(privilegeRepository.findAllById(privilegeIds));
        role.setPrivileges(privileges);
        return roleRepository.save(role);
    }

    @Transactional
    public MerchantUser assignRoleToUser(Long userId, Long roleId) {
        Role role = getRole(roleId);
        MerchantUser user = merchantUserRepository.findById(userId)
                .orElseThrow(() -> new AppException("User not found", HttpStatus.NOT_FOUND));

        if (!user.getMerchantId().equals(merchantScope.merchantId())) {
            throw new AppException("User not found", HttpStatus.NOT_FOUND);
        }

        user.setRoleEntity(role);
        user.setRole(role.getSlug());
        return merchantUserRepository.save(user);
    }
}

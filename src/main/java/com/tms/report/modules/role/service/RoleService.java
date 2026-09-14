package com.tms.report.modules.role.service;

import com.tms.report.core.exception.AppException;
import com.tms.report.core.security.MerchantScope;
import com.tms.report.modules.merchantuser.model.MerchantUser;
import com.tms.report.modules.merchantuser.repository.MerchantUserRepository;
import com.tms.report.modules.role.dto.RoleResponse;
import com.tms.report.modules.role.model.Privilege;
import com.tms.report.modules.role.model.Role;
import com.tms.report.modules.role.repository.PrivilegeRepository;
import com.tms.report.modules.role.repository.RoleRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoleService {

    private final RoleRepository roleRepository;
    private final PrivilegeRepository privilegeRepository;
    private final MerchantUserRepository merchantUserRepository;
    private final MerchantScope merchantScope;

    public List<RoleResponse> listRoles() {
        List<Role> roles = roleRepository.findByMerchantId(merchantScope.merchantId());
        return roles.stream().map(role -> {
            List<MerchantUser> users = merchantUserRepository.findByRoleEntity(role);
            return RoleResponse.from(role, users);
        }).toList();
    }

    public RoleResponse getRoleWithUsers(Long id) {
        Role role = getRole(id);
        List<MerchantUser> users = merchantUserRepository.findByRoleEntity(role);
        return RoleResponse.from(role, users);
    }

    public Role getRole(Long id) {
        return roleRepository.findByMerchantIdAndId(merchantScope.merchantId(), id)
                .orElseThrow(() -> new AppException("Role not found", HttpStatus.NOT_FOUND));
    }

    @Transactional
    public RoleResponse createRole(String name, String slug, String description, Set<Long> privilegeIds) {
        Long merchantId = merchantScope.merchantId();
        if (roleRepository.existsByMerchantIdAndSlug(merchantId, slug)) {
            throw new AppException("A role with this slug already exists", HttpStatus.CONFLICT);
        }

        Set<Privilege> privileges = new HashSet<>();
        if (privilegeIds != null && !privilegeIds.isEmpty()) {
            log.info("Creating role '{}' with privilegeIds: {}", name, privilegeIds);
            List<Privilege> found = privilegeRepository.findAllById(privilegeIds);
            log.info("Found {} privileges from DB: {}", found.size(),
                    found.stream().map(p -> p.getId() + ":" + p.getCode()).toList());
            privileges.addAll(found);
        }

        Role role = Role.builder().merchantId(merchantId).name(name).slug(slug).description(description)
                .systemRole(false).privileges(privileges).build();

        log.info("Role before save - privileges size: {}", role.getPrivileges().size());
        Role saved = roleRepository.save(role);
        log.info("Role after save - id: {}, privileges size: {}", saved.getId(), saved.getPrivileges().size());

        return RoleResponse.from(saved, List.of());
    }

    @Transactional
    public RoleResponse updateRole(Long id, String name, String description, Set<Long> privilegeIds) {
        Role role = getRole(id);

        // For system roles, only allow privilege updates (not name/description changes)
        if (role.isSystemRole()) {
            if (name != null && !name.equals(role.getName())) {
                throw new AppException("System role name cannot be modified", HttpStatus.FORBIDDEN);
            }
            if (description != null && !description.equals(role.getDescription())) {
                throw new AppException("System role description cannot be modified", HttpStatus.FORBIDDEN);
            }
        } else {
            // Non-system roles can update name and description
            if (name != null) {
                role.setName(name);
            }
            if (description != null) {
                role.setDescription(description);
            }
        }

        // Privileges can be updated for all roles
        if (privilegeIds != null) {
            Set<Privilege> privileges = new HashSet<>(privilegeRepository.findAllById(privilegeIds));
            role.setPrivileges(privileges);
        }

        Role saved = roleRepository.save(role);
        List<MerchantUser> users = merchantUserRepository.findByRoleEntity(saved);
        return RoleResponse.from(saved, users);
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

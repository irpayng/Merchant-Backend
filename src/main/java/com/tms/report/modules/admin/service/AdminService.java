package com.tms.report.modules.admin.service;

import com.tms.report.modules.admin.dto.AdminDto;
import com.tms.report.modules.admin.dto.CreateAdminRequest;
import com.tms.report.modules.admin.dto.RoleDto;
import com.tms.report.modules.admin.dto.UpdateAdminRequest;
import com.tms.report.modules.admin.model.Admin;
import com.tms.report.modules.admin.repository.AdminRepository;
import com.tms.report.modules.role.model.Role;
import com.tms.report.modules.role.repository.RoleRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.criteria.JoinType;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminService {

    private final AdminRepository adminRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    public Page<AdminDto> index(Map<String, String> params) {
        int page = Integer.parseInt(params.getOrDefault("page", "1")) - 1;
        int limit = Integer.parseInt(params.getOrDefault("limit", "15"));
        String search = params.get("search");
        String status = params.get("status");
        String roleFilter = params.get("role");

        Pageable pageable = PageRequest.of(page, limit, Sort.by(Sort.Direction.DESC, "createdAt"));

        Specification<Admin> spec = (root, query, cb) -> cb.conjunction();

        if (search != null && !search.isBlank()) {
            String searchLower = search.toLowerCase();
            spec = spec.and((root, query, cb) -> {
                var emailMatch = cb.like(cb.lower(root.get("email")), "%" + searchLower + "%");
                var nameMatch = cb.like(cb.lower(root.get("name")), "%" + searchLower + "%");
                // Search role names via join
                var rolesJoin = root.join("roles", JoinType.LEFT);
                var roleMatch = cb.like(cb.lower(rolesJoin.get("name")), "%" + searchLower + "%");
                query.distinct(true);
                return cb.or(emailMatch, nameMatch, roleMatch);
            });
        }

        if ("blocked".equals(status)) {
            spec = spec.and((root, query, cb) -> cb.isNotNull(root.get("blockedAt")));
        } else if ("active".equals(status)) {
            spec = spec.and((root, query, cb) -> cb.isNull(root.get("blockedAt")));
        }

        return adminRepository.findAll(spec, pageable).map(this::toDto);
    }

    public AdminDto show(Long id) {
        Admin admin = adminRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Admin not found"));
        return toDto(admin);
    }

    @Transactional
    public AdminDto store(CreateAdminRequest request) {
        Admin admin = Admin.builder().name(request.getName()).email(request.getEmail().toLowerCase())
                .phoneNumber(request.getPhoneNumber()).password(passwordEncoder.encode(request.getPassword()))
                .roles(resolveRoles(request.getRoles())).build();
        return toDto(adminRepository.save(admin));
    }

    @Transactional
    public AdminDto update(Long id, UpdateAdminRequest request) {
        Admin admin = adminRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Admin not found"));

        if (request.getName() != null)
            admin.setName(request.getName());
        if (request.getEmail() != null)
            admin.setEmail(request.getEmail().toLowerCase());
        if (request.getPhoneNumber() != null) {
            admin.setPhoneNumber(request.getPhoneNumber().isBlank() ? null : request.getPhoneNumber());
        }
        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            admin.setPassword(passwordEncoder.encode(request.getPassword()));
        }
        if (request.getRoles() != null) {
            admin.setRoles(resolveRoles(request.getRoles()));
        }

        return toDto(adminRepository.save(admin));
    }

    @Transactional
    public void destroy(Long id) {
        Admin admin = adminRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Admin not found"));
        adminRepository.delete(admin);
    }

    @Transactional
    public AdminDto block(Long id, String reason) {
        Admin admin = adminRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Admin not found"));
        admin.setBlockedAt(LocalDateTime.now());
        admin.setBlockedReason(reason);
        return toDto(adminRepository.save(admin));
    }

    @Transactional
    public AdminDto unblock(Long id) {
        Admin admin = adminRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Admin not found"));
        admin.setBlockedAt(null);
        admin.setBlockedReason(null);
        return toDto(adminRepository.save(admin));
    }

    private Set<Role> resolveRoles(java.util.List<String> roleIds) {
        if (roleIds == null || roleIds.isEmpty())
            return new HashSet<>();
        Set<Role> roles = new HashSet<>();
        for (String roleId : roleIds) {
            try {
                roleRepository.findById(Long.parseLong(roleId)).ifPresent(roles::add);
            } catch (NumberFormatException e) {
                roleRepository.findByCode(roleId).ifPresent(roles::add);
            }
        }
        return roles;
    }

    private AdminDto toDto(Admin admin) {
        return AdminDto.builder().id(admin.getId()).name(admin.getName()).email(admin.getEmail())
                .phoneNumber(admin.getPhoneNumber())
                .roles(admin.getRoles().stream()
                        .map(r -> RoleDto.builder().id(r.getId()).name(r.getName()).code(r.getCode()).build()).toList())
                .status(admin.getStatus()).blockedReason(admin.getBlockedReason()).createdAt(admin.getCreatedAt())
                .build();
    }
}

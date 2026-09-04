package com.tms.report.modules.merchantuser.service;

import com.tms.report.core.exception.AppException;
import com.tms.report.core.security.MerchantScope;
import com.tms.report.modules.grpc.service.GrpcClient;
import com.tms.report.modules.merchantuser.model.MerchantUser;
import com.tms.report.modules.merchantuser.repository.MerchantUserRepository;
import com.tms.report.modules.role.model.Role;
import com.tms.report.modules.role.repository.RoleRepository;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class MerchantUserService {

    private final MerchantUserRepository merchantUserRepository;
    private final RoleRepository roleRepository;
    private final MerchantScope merchantScope;
    private final GrpcClient grpcClient;

    public Page<Map<String, Object>> list(Map<String, String> params) {
        Long merchantId = merchantScope.merchantId();
        if (merchantId == null) {
            return Page.empty();
        }

        int page = Integer.parseInt(params.getOrDefault("page", "1"));
        int limit = Integer.parseInt(params.getOrDefault("limit", "15"));
        String search = params.get("search");

        PageRequest pageable = PageRequest.of(Math.max(page - 1, 0), limit, Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<MerchantUser> users;
        if (search != null && !search.isBlank()) {
            users = merchantUserRepository.findAll((root, query, cb) -> {
                String pattern = "%" + search.trim().toLowerCase() + "%";
                return cb.and(cb.equal(root.get("merchantId"), merchantId), cb.or(
                        cb.like(cb.lower(root.get("name")), pattern), cb.like(cb.lower(root.get("email")), pattern)));
            }, pageable);
        } else {
            users = merchantUserRepository.findAll((root, query, cb) -> cb.equal(root.get("merchantId"), merchantId),
                    pageable);
        }

        return users.map(this::toView);
    }

    public List<Map<String, Object>> listRolesForFilter() {
        Long merchantId = merchantScope.merchantId();
        if (merchantId == null) {
            return List.of();
        }
        return roleRepository.findByMerchantId(merchantId).stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", String.valueOf(r.getId()));
            m.put("name", r.getName());
            return m;
        }).toList();
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> data) {
        Long merchantId = merchantScope.merchantId();
        if (merchantId == null) {
            throw new AppException("Not authenticated", HttpStatus.UNAUTHORIZED);
        }

        String name = str(data, "name");
        String email = str(data, "email").toLowerCase().trim();
        String phoneNumber = str(data, "phone_number");
        String password = str(data, "password");
        String pin = str(data, "pin");

        if (name.isBlank() || email.isBlank()) {
            throw new AppException("Name and email are required", HttpStatus.BAD_REQUEST);
        }
        if (password.isBlank()) {
            throw new AppException("Password is required", HttpStatus.BAD_REQUEST);
        }

        // Check for existing user with same email locally
        if (merchantUserRepository.findByEmail(email).isPresent()) {
            throw new AppException("A user with this email already exists", HttpStatus.CONFLICT);
        }

        // Generate a username for the operator (email prefix + merchantId suffix)
        String username = email.split("@")[0] + "_" + merchantId;

        // Default PIN if not provided (staff can change later via POS)
        if (pin.isBlank()) {
            pin = "0000";
        }

        // Create operator in tms-user (credentials stored there)
        Map<String, Object> operatorResult = grpcClient.createOperator(merchantId, username, password, // plain text —
                                                                                                       // tms-user will
                                                                                                       // hash it
                name, email, phoneNumber, pin, // plain text — tms-user will hash it
                true, // dashboardEnabled
                true // posEnabled (staff can access both dashboard and POS)
        );

        if (!Boolean.TRUE.equals(operatorResult.get("success"))) {
            String reason = (String) operatorResult.get("reason");
            String message = (String) operatorResult.get("message");

            if ("username_exists".equals(reason) || "email_exists".equals(reason)) {
                throw new AppException("A user with this email already exists", HttpStatus.CONFLICT);
            }
            log.error("Failed to create operator in tms-user: {} - {}", reason, message);
            throw new AppException(message != null ? message : "Failed to create staff user", HttpStatus.BAD_REQUEST);
        }

        Long operatorId = ((Number) operatorResult.get("operator_id")).longValue();
        log.info("Created operator in tms-user: operatorId={} merchantId={} email={}", operatorId, merchantId, email);

        // Create local MerchantUser record (auth handled via tms-user operators)
        MerchantUser user = MerchantUser.builder().merchantId(merchantId).operatorId(operatorId).name(name).email(email)
                .phoneNumber(phoneNumber).role(MerchantUser.ROLE_CASHIER).status(MerchantUser.STATUS_ACTIVE)
                .emailVerifiedAt(LocalDateTime.now())
                .invitedBy(merchantScope.current() != null ? merchantScope.current().getId() : null).build();

        // Assign roles if provided
        @SuppressWarnings("unchecked")
        List<String> roleIds = data.get("roles") instanceof List ? (List<String>) data.get("roles") : null;
        if (roleIds != null && !roleIds.isEmpty()) {
            Long firstRoleId = Long.parseLong(roleIds.get(0));
            Role role = roleRepository.findByMerchantIdAndId(merchantId, firstRoleId).orElse(null);
            if (role != null) {
                user.setRoleEntity(role);
                user.setRole(role.getSlug());
            }
        }

        merchantUserRepository.save(user);

        return toView(user);
    }

    public Map<String, Object> show(Long userId) {
        Long merchantId = merchantScope.merchantId();
        if (merchantId == null) {
            throw new AppException("Not authenticated", HttpStatus.UNAUTHORIZED);
        }

        MerchantUser user = merchantUserRepository.findById(userId)
                .orElseThrow(() -> new AppException("User not found", HttpStatus.NOT_FOUND));

        if (!user.getMerchantId().equals(merchantId)) {
            throw new AppException("User not found", HttpStatus.NOT_FOUND);
        }

        return toView(user);
    }

    @Transactional
    public Map<String, Object> update(Long userId, Map<String, Object> data) {
        Long merchantId = merchantScope.merchantId();
        if (merchantId == null) {
            throw new AppException("Not authenticated", HttpStatus.UNAUTHORIZED);
        }

        MerchantUser user = merchantUserRepository.findById(userId)
                .orElseThrow(() -> new AppException("User not found", HttpStatus.NOT_FOUND));

        if (!user.getMerchantId().equals(merchantId)) {
            throw new AppException("User not found", HttpStatus.NOT_FOUND);
        }

        // Update name if provided
        String name = str(data, "name");
        if (!name.isBlank()) {
            user.setName(name);
        }

        // Update phone number if provided
        String phoneNumber = str(data, "phone_number");
        if (!phoneNumber.isBlank()) {
            user.setPhoneNumber(phoneNumber);
        }

        // Update role if provided
        @SuppressWarnings("unchecked")
        List<String> roleIds = data.get("roles") instanceof List ? (List<String>) data.get("roles") : null;
        if (roleIds != null && !roleIds.isEmpty()) {
            Long roleId = Long.parseLong(roleIds.get(0));
            Role role = roleRepository.findByMerchantIdAndId(merchantId, roleId)
                    .orElseThrow(() -> new AppException("Role not found", HttpStatus.NOT_FOUND));
            user.setRoleEntity(role);
            user.setRole(role.getSlug());
        }

        merchantUserRepository.save(user);

        return toView(user);
    }

    @Transactional
    public void assignRoles(Long userId, List<String> roleIds) {
        Long merchantId = merchantScope.merchantId();
        MerchantUser user = merchantUserRepository.findById(userId)
                .orElseThrow(() -> new AppException("User not found", HttpStatus.NOT_FOUND));

        if (!user.getMerchantId().equals(merchantId)) {
            throw new AppException("User not found", HttpStatus.NOT_FOUND);
        }

        if (roleIds != null && !roleIds.isEmpty()) {
            Long roleId = Long.parseLong(roleIds.get(0));
            Role role = roleRepository.findByMerchantIdAndId(merchantId, roleId)
                    .orElseThrow(() -> new AppException("Role not found", HttpStatus.NOT_FOUND));
            user.setRoleEntity(role);
            user.setRole(role.getSlug());
            merchantUserRepository.save(user);
        }
    }

    @Transactional
    public void unassignRoles(Long userId, List<String> roleIds) {
        Long merchantId = merchantScope.merchantId();
        MerchantUser user = merchantUserRepository.findById(userId)
                .orElseThrow(() -> new AppException("User not found", HttpStatus.NOT_FOUND));

        if (!user.getMerchantId().equals(merchantId)) {
            throw new AppException("User not found", HttpStatus.NOT_FOUND);
        }

        user.setRoleEntity(null);
        user.setRole(MerchantUser.ROLE_CASHIER);
        merchantUserRepository.save(user);
    }

    private Map<String, Object> toView(MerchantUser u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("name", u.getName());
        m.put("email", u.getEmail());
        m.put("phone_number", u.getPhoneNumber());
        m.put("status", u.getStatus());
        m.put("created_at", u.getCreatedAt() != null ? u.getCreatedAt().toString() : null);

        // Roles array for frontend
        List<Map<String, Object>> roles = new ArrayList<>();
        if (u.getRoleEntity() != null) {
            Map<String, Object> roleMap = new LinkedHashMap<>();
            roleMap.put("id", String.valueOf(u.getRoleEntity().getId()));
            roleMap.put("name", u.getRoleEntity().getName());
            roles.add(roleMap);
        }
        m.put("roles", roles);

        return m;
    }

    private String str(Map<String, Object> data, String key) {
        Object v = data.get(key);
        return v != null ? v.toString() : "";
    }
}

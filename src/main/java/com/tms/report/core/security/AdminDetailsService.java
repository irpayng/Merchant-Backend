package com.tms.report.core.security;

import com.tms.report.modules.admin.model.Admin;
import com.tms.report.modules.admin.repository.AdminRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdminDetailsService implements UserDetailsService {

    private final AdminRepository adminRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        Admin admin = adminRepository.findByEmail(username).or(() -> adminRepository.findByPhoneNumber(username))
                .orElseThrow(() -> new UsernameNotFoundException("Admin not found: " + username));
        return new AdminDetails(admin);
    }
}

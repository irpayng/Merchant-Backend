package com.tms.report.core.security;

import com.tms.report.modules.merchantuser.model.MerchantUser;
import com.tms.report.modules.merchantuser.repository.MerchantUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Loads a merchant dashboard login (owner/cashier) for authentication. Accepts
 * email or phone as the username.
 */
@Service
@RequiredArgsConstructor
public class MerchantUserDetailsService implements UserDetailsService {

    private final MerchantUserRepository merchantUserRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        String key = username == null ? "" : username.toLowerCase();
        MerchantUser user = merchantUserRepository.findByEmail(key)
                .or(() -> merchantUserRepository.findByPhoneNumber(username))
                .orElseThrow(() -> new UsernameNotFoundException("Account not found: " + username));
        return new MerchantUserDetails(user);
    }
}

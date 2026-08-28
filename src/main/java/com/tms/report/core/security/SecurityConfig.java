package com.tms.report.core.security;

import com.tms.report.modules.audit.filter.AuditLoggingFilter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;
    private final AuditLoggingFilter auditLoggingFilter;
    private final MerchantUserDetailsService merchantUserDetailsService;
    private final ObjectMapper objectMapper;

    /**
     * Comma-separated list of browser origins allowed to call this API. Defaults
     * cover local dev, the internal *.irpay.local ingress, and the public portal
     * hosts (staging-sm / sm). Override per-environment with CORS_ALLOWED_ORIGINS
     * if a new host is added.
     */
    @Value("${app.cors.allowed-origins:" + "http://localhost:3000,http://127.0.0.1:3000,"
            + "http://super-merchant.irpay.local,https://super-merchant.irpay.local,"
            + "http://super-merchant-api.irpay.local,https://super-merchant-api.irpay.local,"
            + "https://staging-sm.irpay.dev,https://sm.irpay.dev}")
    private String allowedOrigins;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.cors(cors -> cors.configurationSource(corsConfigurationSource())).csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        // Async (SSE) and error dispatches are internal continuations of an
                        // already-authorized REQUEST dispatch. Spring Security 7 filters all
                        // dispatcher types by default, so without this the ASYNC re-dispatch of
                        // an SseEmitter response re-runs authorization with no SecurityContext
                        // (STATELESS — nothing to restore), throwing AuthorizationDeniedException
                        // after the SSE response is already committed ("response is already
                        // committed"). The initial REQUEST dispatch is still fully authenticated.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers("/health", "/error").permitAll()
                        .requestMatchers("/actuator/prometheus", "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/auth/forgot-password").permitAll()
                        .requestMatchers(HttpMethod.POST, "/auth/reset-password").permitAll()
                        .requestMatchers(HttpMethod.POST, "/auth/request-activation").permitAll()
                        .requestMatchers(HttpMethod.POST, "/auth/activate").permitAll()
                        .requestMatchers(HttpMethod.POST, "/auth/activate-otp").permitAll().anyRequest()
                        .authenticated())
                .exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    response.getWriter()
                            .write(objectMapper.writeValueAsString(Map.of("code", 401, "message", "Unauthenticated")));
                }).accessDeniedHandler((request, response, accessDeniedException) -> {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json");
                    response.getWriter()
                            .write(objectMapper.writeValueAsString(Map.of("code", 403, "message", "Forbidden")));
                })).sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authenticationProvider(authenticationProvider())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(auditLoggingFilter, JwtAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(merchantUserDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(
                Arrays.stream(allowedOrigins.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList());
        config.setAllowedMethods(List.of("*"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Authorization"));
        config.setAllowCredentials(true);
        config.setMaxAge(86400L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // favre bcrypt is the fastest Java bcrypt implementation
        // and handles Laravel's $2y$ prefix natively
        return new PasswordEncoder() {
            @Override
            public String encode(CharSequence rawPassword) {
                return at.favre.lib.crypto.bcrypt.BCrypt.withDefaults().hashToString(12,
                        rawPassword.toString().toCharArray());
            }

            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                if (encodedPassword == null || rawPassword == null)
                    return false;
                // favre bcrypt handles $2a$, $2b$, $2y$ natively
                return at.favre.lib.crypto.bcrypt.BCrypt.verifyer().verify(rawPassword.toString().toCharArray(),
                        encodedPassword).verified;
            }
        };
    }
}

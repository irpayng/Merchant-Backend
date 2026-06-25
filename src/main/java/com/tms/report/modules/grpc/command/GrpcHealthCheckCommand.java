package com.tms.report.modules.grpc.command;

import com.tms.report.modules.grpc.config.GrpcProperties;
import com.tms.report.modules.grpc.service.GrpcClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Equivalent of `php artisan grpc:health`. Run with:
 * --spring.profiles.active=grpc-health
 */
@Slf4j
@Component
@Profile("grpc-health")
@RequiredArgsConstructor
public class GrpcHealthCheckCommand implements CommandLineRunner {

    private final GrpcClient grpcClient;
    private final GrpcProperties props;

    @Override
    public void run(String... args) {
        log.info("Checking gRPC connection to tms-api...");
        log.info("API URL: {}", props.getApiUrl());

        try {
            boolean healthy = grpcClient.health();
            if (healthy) {
                log.info("✓ gRPC service is healthy");
            } else {
                log.error("✗ gRPC service is not healthy");
                System.exit(1);
            }
        } catch (Exception e) {
            log.error("✗ Failed to connect to gRPC service: {}", e.getMessage());
            System.exit(1);
        }
    }
}

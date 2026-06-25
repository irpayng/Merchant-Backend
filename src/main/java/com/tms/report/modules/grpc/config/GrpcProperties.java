package com.tms.report.modules.grpc.config;

import java.util.HashMap;
import java.util.Map;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "grpc")
public class GrpcProperties {

    /**
     * @deprecated No longer used — tms-report-java now calls microservices directly
     *             via gRPC. Kept for backward compatibility with existing configs.
     */
    @Deprecated
    private String apiUrl = "http://localhost:8000";

    /**
     * @deprecated No longer used — gRPC calls don't go through tms-api anymore.
     */
    @Deprecated
    private String apiKey;

    private int timeout = 30;
    private int batchTimeout = 120;
    private boolean logging = true;
    private Map<String, ServiceEndpoint> services = new HashMap<>();

    public ServiceEndpoint getService(String name) {
        return services.getOrDefault(name, new ServiceEndpoint());
    }
}

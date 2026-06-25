package com.tms.report.modules.grpc.service;

import com.shared.util.Ulid;
import com.tms.report.core.security.AdminDetails;
import com.tms.report.modules.grpc.config.GrpcProperties;
import com.tms.report.modules.grpc.config.ServiceEndpoint;
import com.tms.report.modules.grpc.exception.GrpcException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * HTTP client for sending commands to the settlement microservice. Matches the
 * PHP GrpcClient::command() pattern — sends JSON POST to
 * /grpc/command/{method}.
 */
@Slf4j
@Service
public class SettlementHttpClient {

    private final String baseUrl;
    private final String apiKey;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public SettlementHttpClient(GrpcProperties props, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        ServiceEndpoint ep = props.getService("settlement");
        this.baseUrl = "http://" + ep.getHost() + ":" + ep.getPort();
        this.apiKey = props.getApiKey() != null ? props.getApiKey() : "secret";
        // HTTP/1.1: avoid JDK HTTP/2 stale pooled-connection hangs across rolling
        // restarts.
        this.httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10)).build();
        log.info("SettlementHttpClient initialized: {}", baseUrl);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> command(String method, Map<String, Object> data) {
        String reference = Ulid.generate();
        data.put("reference", reference);
        data.put("actor", buildActor());

        log.info("Settlement → {} [ref={}]", method, reference);

        try {
            String json = objectMapper.writeValueAsString(data);
            log.info("Settlement payload: {}", json);

            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(baseUrl + "/grpc/command/" + method))
                    .timeout(Duration.ofSeconds(120)).header("Content-Type", "application/json")
                    .header("Accept", "application/json").header("X-Grpc-Api-Key", apiKey)
                    .header("X-Request-Id", reference).POST(HttpRequest.BodyPublishers.ofString(json)).build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);

            log.info("Settlement ✓ {} [ref={}]: status={}, success={}", method, reference, response.statusCode(),
                    result.get("success"));

            if (response.statusCode() >= 400 || !Boolean.TRUE.equals(result.get("success"))) {
                String message = result.getOrDefault("message", "Command failed").toString();
                throw new GrpcException("Settlement command failed: " + method + " - " + message, "COMMAND_FAILED",
                        Map.of("method", method, "reference", reference));
            }

            return result;
        } catch (GrpcException e) {
            throw e;
        } catch (Exception e) {
            log.error("Settlement ✗ {} [ref={}]: {}", method, reference, e.getMessage());
            throw new GrpcException("Settlement service unavailable: " + e.getMessage(), "UNAVAILABLE",
                    Map.of("method", method, "reference", reference));
        }
    }

    private Map<String, Object> buildActor() {
        Map<String, Object> actor = new LinkedHashMap<>();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AdminDetails details) {
            actor.put("id", String.valueOf(details.getAdmin().getId()));
            actor.put("name", details.getAdmin().getName());
            actor.put("email", details.getAdmin().getEmail());
        } else {
            actor.put("id", "system");
            actor.put("name", "System");
            actor.put("email", "system@tms.local");
        }
        return actor;
    }
}

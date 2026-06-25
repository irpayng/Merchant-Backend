package com.tms.report.modules.grpc.service;

import com.shared.util.Ulid;
import com.tms.report.modules.grpc.config.GrpcProperties;
import com.tms.report.modules.grpc.config.ServiceEndpoint;
import com.tms.report.modules.grpc.exception.GrpcException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * HTTP client for sending commands to the ledger microservice's
 * {@code /grpc/command/{method}} endpoint. Used for admin-driven ledger
 * operations that aren't part of the automatic flows — chiefly attributing
 * provider-float variance out of suspense when an admin resolves a
 * PROVIDER_FLOAT_* incident.
 */
@Slf4j
@Service
public class LedgerHttpClient {

    private final String baseUrl;
    private final String apiKey;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public LedgerHttpClient(GrpcProperties props, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        ServiceEndpoint ep = props.getService("ledger");
        this.baseUrl = "http://" + ep.getHost() + ":" + ep.getPort();
        this.apiKey = props.getApiKey() != null ? props.getApiKey() : "secret";
        // HTTP/1.1: avoid JDK HTTP/2 stale pooled-connection hangs across rolling
        // restarts.
        this.httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10)).build();
        log.info("LedgerHttpClient initialized: {}", baseUrl);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> command(String method, Map<String, Object> data) {
        String reference = data.get("reference") != null ? data.get("reference").toString() : Ulid.generate();
        data.putIfAbsent("reference", reference);
        try {
            String json = objectMapper.writeValueAsString(data);
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(baseUrl + "/grpc/command/" + method))
                    .timeout(Duration.ofSeconds(60)).header("Content-Type", "application/json")
                    .header("X-Grpc-Api-Key", apiKey).POST(HttpRequest.BodyPublishers.ofString(json)).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
            if (response.statusCode() >= 400 || !Boolean.TRUE.equals(result.get("success"))) {
                String message = result.getOrDefault("message", "Command failed").toString();
                throw new GrpcException("Ledger command failed: " + method + " - " + message, "COMMAND_FAILED",
                        Map.of("method", method, "reference", reference));
            }
            return result;
        } catch (GrpcException e) {
            throw e;
        } catch (Exception e) {
            log.error("Ledger ✗ {} [ref={}]: {}", method, reference, e.getMessage());
            throw new GrpcException("Ledger service unavailable: " + e.getMessage(), "UNAVAILABLE",
                    Map.of("method", method, "reference", reference));
        }
    }
}

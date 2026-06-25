package com.tms.report.modules.grpc.service;

import com.tms.report.core.exception.AppException;
import com.tms.report.modules.grpc.config.GrpcProperties;
import com.tms.report.modules.grpc.config.ServiceEndpoint;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Minimal admin HTTP passthrough for transaction-service commands that are
 * exposed on the {@code /grpc/command/{method}} endpoint but not yet promoted
 * to typed gRPC. Used for low-volume admin operations like pos-lease waivers
 * where adding a proto definition would be overkill.
 *
 * <p>
 * Authenticates with the same shared {@code grpc.api.key} as the typed gRPC
 * stubs so the existing admin-actor headers and audit logging downstream still
 * apply.
 */
@Slf4j
@Service
public class TransactionAdminClient {

    private final String transactionBaseUrl;
    private final String apiKey;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public TransactionAdminClient(GrpcProperties props, ObjectMapper objectMapper,
            @Value("${grpc.api.key:secret}") String apiKey) {
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        ServiceEndpoint ep = props.getService("transaction");
        this.transactionBaseUrl = "http://" + ep.getHost() + ":" + ep.getPort();
        // HTTP/1.1: avoid JDK HTTP/2 stale pooled-connection hangs across rolling
        // restarts.
        this.httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10)).build();
        log.info("TransactionAdminClient initialized: {}", transactionBaseUrl);
    }

    public Map<String, Object> waivePosLease(long userId, String serial, long adminId, String reason) {
        return invoke("WaivePosLease", Map.of("user_id", userId, "serial", serial, "admin_id", adminId, "reason",
                reason == null ? "" : reason));
    }

    public Map<String, Object> revokePosLeaseWaiver(long userId, String serial, long adminId, String reason) {
        return invoke("RevokePosLeaseWaiver", Map.of("user_id", userId, "serial", serial, "admin_id", adminId, "reason",
                reason == null ? "" : reason));
    }

    public Map<String, Object> listPosLeases(Long userId, String serial, String statusCode, int page, int limit) {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        if (userId != null) {
            body.put("user_id", userId);
        }
        if (serial != null) {
            body.put("serial", serial);
        }
        if (statusCode != null) {
            body.put("status_code", statusCode);
        }
        body.put("page", page);
        body.put("limit", limit);
        return invoke("ListPosLeases", body);
    }

    /**
     * Trigger a back-office card reversal. Posts to the transaction service's
     * dedicated, api-key-authed admin reversal endpoint (not the
     * {@code /grpc/command} bridge — the reversal calls the processor synchronously
     * and must not run inside an outer transaction).
     */
    public Map<String, Object> reverseCardTransaction(String reference, String adminEmail) {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("reference", reference);
        body.put("admin_email", adminEmail == null ? "" : adminEmail);
        return post("/admin/reversals/complete", body);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, Map<String, Object> body) {
        try {
            byte[] payload = objectMapper.writeValueAsBytes(body);
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(transactionBaseUrl + path))
                    .timeout(Duration.ofSeconds(60)).header("Content-Type", "application/json")
                    .header("X-Grpc-Api-Key", apiKey).POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
            if (response.statusCode() >= 400) {
                String message = result.getOrDefault("message", "Request failed").toString();
                // Surface the upstream status (e.g. 422 "no reversal data stored")
                // as that same client-error status, not a generic 500 — otherwise
                // the UI shows a scary "Internal server error" for an expected
                // validation outcome.
                HttpStatus status = HttpStatus.resolve(response.statusCode());
                if (status == null || !status.is4xxClientError()) {
                    status = HttpStatus.UNPROCESSABLE_ENTITY;
                }
                throw new AppException(message, status);
            }
            return result;
        } catch (AppException e) {
            throw e;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("Admin POST {} failed: {}", path, e.getMessage());
            throw new RuntimeException("Transaction service unavailable: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invoke(String method, Map<String, Object> body) {
        try {
            byte[] payload = objectMapper.writeValueAsBytes(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(transactionBaseUrl + "/grpc/command/" + method)).timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json").header("X-Grpc-Api-Key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
            if (response.statusCode() >= 400) {
                String message = result.getOrDefault("message", "Request failed").toString();
                throw new RuntimeException(message);
            }
            return result;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("Admin command {} failed: {}", method, e.getMessage());
            throw new RuntimeException("Transaction service unavailable: " + e.getMessage(), e);
        }
    }
}

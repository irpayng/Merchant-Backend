package com.tms.report.modules.grpc.service;

import com.tms.report.modules.grpc.config.GrpcProperties;
import com.tms.report.modules.grpc.config.ServiceEndpoint;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

/**
 * HTTP client for forwarding file uploads to the config microservice. gRPC is
 * not suited for binary file transfers — this uses the config service's REST
 * multipart endpoints directly via internal ClusterIP.
 */
@Slf4j
@Service
public class ConfigHttpClient {

    private final String configBaseUrl;
    private final String apiKey;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public ConfigHttpClient(GrpcProperties props, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        // Use the config service endpoint directly — Quarkus shares HTTP + gRPC on the
        // same port.
        // In K8s the service port is 80; locally it's 8086.
        ServiceEndpoint ep = props.getService("config");
        this.configBaseUrl = "http://" + ep.getHost() + ":" + ep.getPort();
        this.apiKey = props.getApiKey() != null ? props.getApiKey() : "secret";
        // HTTP/1.1: avoid JDK HTTP/2 stale pooled-connection hangs across rolling
        // restarts.
        this.httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10)).build();
        log.info("ConfigHttpClient initialized: {}", configBaseUrl);
    }

    /**
     * Forward a file upload to config service's terminal import endpoint. POST
     * /terminals/upload (multipart/form-data with 'file' field)
     */
    public Map<String, Object> uploadTerminals(MultipartFile file) {
        return uploadFile("/terminals/upload", file, null);
    }

    /**
     * Forward a file upload to config service's TID import endpoint. POST
     * /tids/upload (multipart/form-data with 'file' and 'internal' fields)
     */
    public Map<String, Object> uploadTids(MultipartFile file, boolean internal) {
        return uploadFile("/tids/upload", file, internal);
    }

    /**
     * Forward a TID file upload with an optional processor scope applied to every
     * row (a per-row {@code processor} column in the file still wins). POST
     * /tids/upload (multipart with 'file', 'internal' and 'processor' fields).
     */
    public Map<String, Object> uploadTids(MultipartFile file, boolean internal, String processor) {
        if (processor != null && !processor.isBlank()) {
            return uploadFile("/tids/upload", file, internal, Map.of("processor", processor.trim()));
        }
        return uploadFile("/tids/upload", file, internal);
    }

    /**
     * Forward a file upload to the aggregator-dispatch endpoint. POST
     * /aggregator-terminals/upload (multipart/form-data with 'file' and
     * 'aggregator_id').
     */
    public Map<String, Object> uploadAggregatorTerminals(MultipartFile file, long aggregatorId) {
        return uploadFile("/aggregator-terminals/upload", file, null,
                Map.of("aggregator_id", String.valueOf(aggregatorId)));
    }

    /**
     * GET passthrough for plain JSON responses on the config service.
     */
    public Map<String, Object> getJson(String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(configBaseUrl + path))
                    .timeout(Duration.ofSeconds(30)).GET().build();
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
            log.error("GET {}{} failed: {}", configBaseUrl, path, e.getMessage());
            throw new RuntimeException("Config service unavailable: " + e.getMessage(), e);
        }
    }

    /**
     * Plain DELETE passthrough used by aggregator-terminal unassign.
     */
    public Map<String, Object> deleteJson(String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(configBaseUrl + path))
                    .timeout(Duration.ofSeconds(30)).DELETE().build();
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
            log.error("DELETE {}{} failed: {}", configBaseUrl, path, e.getMessage());
            throw new RuntimeException("Config service unavailable: " + e.getMessage(), e);
        }
    }

    /**
     * POST passthrough for plain JSON request/response. Used by admin commands like
     * terminal lock/unlock and the maintenance-mode toggle.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> postJson(String path, Map<String, Object> body) {
        try {
            String payload = body != null ? objectMapper.writeValueAsString(body) : "{}";
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(configBaseUrl + path))
                    .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build();
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
            log.error("POST {}{} failed: {}", configBaseUrl, path, e.getMessage());
            throw new RuntimeException("Config service unavailable: " + e.getMessage(), e);
        }
    }

    /**
     * Invoke an admin command on config-service's {@code /grpc/command/{method}}
     * surface. Adds the {@code X-Grpc-Api-Key} header that the resource gates on.
     * Use this for write operations the admin UI needs to trigger via tms-report
     * (e.g. {@code BackfillTerminalMapped}). Read-only or non-gated endpoints
     * should keep using {@link #postJson}.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> postGrpcCommand(String method, Map<String, Object> body) {
        String path = "/grpc/command/" + method;
        try {
            String payload = body != null ? objectMapper.writeValueAsString(body) : "{}";
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(configBaseUrl + path))
                    .timeout(Duration.ofSeconds(120)).header("Content-Type", "application/json")
                    .header("Accept", "application/json").header("X-Grpc-Api-Key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
            if (response.statusCode() >= 400 || !Boolean.TRUE.equals(result.get("success"))) {
                String message = result.getOrDefault("message", "Command failed").toString();
                throw new RuntimeException(message);
            }
            return result;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("Config grpc command {} failed: {}", method, e.getMessage());
            throw new RuntimeException("Config service unavailable: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> uploadFile(String path, MultipartFile file, Boolean internal) {
        return uploadFile(path, file, internal, Map.of());
    }

    private Map<String, Object> uploadFile(String path, MultipartFile file, Boolean internal,
            Map<String, String> extraFields) {
        try {
            String boundary = "----FormBoundary" + UUID.randomUUID().toString().replace("-", "");
            byte[] body = buildMultipartBody(boundary, file, internal, extraFields);

            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(configBaseUrl + path))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();

            log.info("Forwarding file upload to {}{} ({})", configBaseUrl, path, file.getOriginalFilename());

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);

            if (response.statusCode() >= 400) {
                String message = result.getOrDefault("message", "Upload failed").toString();
                // 4xx from config is a client/validation error (e.g. an import where
                // every row failed) — surface it as a clean 400 with the reason rather
                // than a generic 500 "Internal server error".
                if (response.statusCode() < 500) {
                    throw new IllegalArgumentException(message);
                }
                throw new RuntimeException(message);
            }

            return result;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("File upload to config service failed: {}", e.getMessage());
            throw new RuntimeException("Config service unavailable: " + e.getMessage(), e);
        }
    }

    private byte[] buildMultipartBody(String boundary, MultipartFile file, Boolean internal,
            Map<String, String> extraFields) throws Exception {
        var baos = new java.io.ByteArrayOutputStream();
        String crlf = "\r\n";

        // File part
        baos.write(("--" + boundary + crlf).getBytes(StandardCharsets.UTF_8));
        baos.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + file.getOriginalFilename() + "\""
                + crlf).getBytes(StandardCharsets.UTF_8));
        baos.write(("Content-Type: " + file.getContentType() + crlf).getBytes(StandardCharsets.UTF_8));
        baos.write(crlf.getBytes(StandardCharsets.UTF_8));
        baos.write(file.getBytes());
        baos.write(crlf.getBytes(StandardCharsets.UTF_8));

        // Internal field (for TID uploads)
        if (internal != null) {
            baos.write(("--" + boundary + crlf).getBytes(StandardCharsets.UTF_8));
            baos.write(("Content-Disposition: form-data; name=\"internal\"" + crlf).getBytes(StandardCharsets.UTF_8));
            baos.write(crlf.getBytes(StandardCharsets.UTF_8));
            baos.write(internal.toString().getBytes(StandardCharsets.UTF_8));
            baos.write(crlf.getBytes(StandardCharsets.UTF_8));
        }

        // Extra string form fields (e.g. aggregator_id)
        if (extraFields != null) {
            for (Map.Entry<String, String> entry : extraFields.entrySet()) {
                baos.write(("--" + boundary + crlf).getBytes(StandardCharsets.UTF_8));
                baos.write(("Content-Disposition: form-data; name=\"" + entry.getKey() + "\"" + crlf)
                        .getBytes(StandardCharsets.UTF_8));
                baos.write(crlf.getBytes(StandardCharsets.UTF_8));
                baos.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                baos.write(crlf.getBytes(StandardCharsets.UTF_8));
            }
        }

        // Closing boundary
        baos.write(("--" + boundary + "--" + crlf).getBytes(StandardCharsets.UTF_8));
        return baos.toByteArray();
    }
}

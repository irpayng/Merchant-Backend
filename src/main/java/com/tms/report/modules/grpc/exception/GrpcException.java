package com.tms.report.modules.grpc.exception;

import java.util.Map;
import lombok.Getter;

@Getter
public class GrpcException extends RuntimeException {

    private final String errorCode;
    private final Map<String, Object> details;

    public GrpcException(String message, String errorCode, Map<String, Object> details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details != null ? details : Map.of();
    }

    public GrpcException(String message, String errorCode) {
        this(message, errorCode, Map.of());
    }

    public GrpcException(String message) {
        this(message, "UNKNOWN", Map.of());
    }

    public Map<String, Object> toMap() {
        return Map.of("code", errorCode, "message", getMessage(), "details", details);
    }
}

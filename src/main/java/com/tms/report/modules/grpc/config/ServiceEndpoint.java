package com.tms.report.modules.grpc.config;

import lombok.Data;

@Data
public class ServiceEndpoint {
    private String host = "localhost";
    private int port = 8080;
}

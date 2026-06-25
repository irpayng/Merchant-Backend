package com.tms.report.modules.terminal.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRawValue;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Read-only mirror of {@code config_service.terminal_metrics} populated via
 * PostgreSQL logical replication.
 *
 * <p>
 * The {@code raw_payload} JSONB column is exposed to the UI as a raw JSON
 * object (not an escaped string) thanks to {@link JsonRawValue}. PostgreSQL
 * replicates JSONB as text on the wire, which Spring stores fine in a
 * {@code String} field.
 */
@Entity
@Table(name = "terminal_metrics")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TerminalMetric {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "terminal_id")
    @JsonProperty("terminal_id")
    private Long terminalId;

    private String serial;

    private String model;
    private String vendor;

    @Column(name = "os_version")
    @JsonProperty("os_version")
    private String osVersion;

    @Column(name = "sdk_version")
    @JsonProperty("sdk_version")
    private String sdkVersion;

    @Column(name = "firmware_version")
    @JsonProperty("firmware_version")
    private String firmwareVersion;

    @Column(name = "kernel_version")
    @JsonProperty("kernel_version")
    private String kernelVersion;

    @Column(name = "app_version")
    @JsonProperty("app_version")
    private String appVersion;

    @Column(name = "battery_pct")
    @JsonProperty("battery_pct")
    private Integer batteryPct;

    @Column(name = "battery_temp_c")
    @JsonProperty("battery_temp_c")
    private BigDecimal batteryTempC;

    @Column(name = "battery_voltage_mv")
    @JsonProperty("battery_voltage_mv")
    private Integer batteryVoltageMv;

    @Column(name = "battery_plugged")
    @JsonProperty("battery_plugged")
    private Boolean batteryPlugged;

    @Column(name = "battery_health")
    @JsonProperty("battery_health")
    private String batteryHealth;

    @Column(name = "ram_total_bytes")
    @JsonProperty("ram_total_bytes")
    private Long ramTotalBytes;

    @Column(name = "ram_avail_bytes")
    @JsonProperty("ram_avail_bytes")
    private Long ramAvailBytes;

    @Column(name = "storage_total_bytes")
    @JsonProperty("storage_total_bytes")
    private Long storageTotalBytes;

    @Column(name = "storage_avail_bytes")
    @JsonProperty("storage_avail_bytes")
    private Long storageAvailBytes;

    @Column(name = "network_type")
    @JsonProperty("network_type")
    private String networkType;

    @Column(name = "signal_strength")
    @JsonProperty("signal_strength")
    private Integer signalStrength;

    @Column(name = "carrier_name")
    @JsonProperty("carrier_name")
    private String carrierName;

    @Column(name = "printer_status")
    @JsonProperty("printer_status")
    private Integer printerStatus;

    @Column(name = "uptime_ms")
    @JsonProperty("uptime_ms")
    private Long uptimeMs;

    @Column(name = "boot_count")
    @JsonProperty("boot_count")
    private Long bootCount;

    private BigDecimal latitude;

    private BigDecimal longitude;

    @Column(name = "location_accuracy_m")
    @JsonProperty("location_accuracy_m")
    private Float locationAccuracyM;

    @Column(name = "location_at")
    @JsonProperty("location_at")
    private LocalDateTime locationAt;

    @Column(name = "location_permission")
    @JsonProperty("location_permission")
    private Boolean locationPermission;

    @Column(name = "location_services_enabled")
    @JsonProperty("location_services_enabled")
    private Boolean locationServicesEnabled;

    @Column(name = "raw_payload", columnDefinition = "jsonb")
    @JsonProperty("raw_payload")
    @JsonRawValue
    private String rawPayload;

    @Column(name = "collected_at")
    @JsonProperty("collected_at")
    private LocalDateTime collectedAt;

    @Column(name = "created_at")
    @JsonProperty("created_at")
    private LocalDateTime createdAt;
}

package com.tms.report.modules.privilege.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Entity
@Table(name = "privileges", schema = "supermerchant")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Privilege {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    private String name;

    @Column(unique = true)
    private String code;

    private String description;

    @JsonIgnore
    @Column(name = "modules")
    @JdbcTypeCode(SqlTypes.JSON)
    private String modulesRaw;

    @Transient
    public List<String> getModules() {
        if (modulesRaw == null)
            return List.of();
        try {
            return MAPPER.readValue(modulesRaw, new TypeReference<>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    @JsonIgnore
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @JsonIgnore
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}

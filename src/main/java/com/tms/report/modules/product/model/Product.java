package com.tms.report.modules.product.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "products")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @Column(unique = true)
    private String code;

    /**
     * Lifecycle marker maintained by config-service's DataSeeder. {@code active}
     * means the product is in the canonical seed file and should appear in admin
     * filter dropdowns. {@code legacy} means the row exists only because historical
     * {@code transactions.product_id} rows reference it (camelCase pre-rename rows
     * like {@code bankTransfer}, the orphan {@code complete}, etc.) and must be
     * hidden from new-transaction-facing UI.
     */
    private String status;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}

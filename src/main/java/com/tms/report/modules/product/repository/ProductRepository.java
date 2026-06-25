package com.tms.report.modules.product.repository;

import com.tms.report.modules.product.model.Product;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ProductRepository extends JpaRepository<Product, Long> {

    /**
     * Returns only active products for the admin filter dropdowns. Excludes rows
     * marked {@code status='legacy'} by the config-service seeder — those exist
     * only as inert FK targets for historical {@code transactions.product_id}
     * values (camelCase pre-rename rows like {@code bankTransfer}, the orphan
     * {@code complete}, etc.) and must not be surfaced as filter options.
     */
    @Query("SELECT p FROM Product p WHERE p.status = 'active' ORDER BY p.name")
    List<Product> findAllValid();
}

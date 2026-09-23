package com.example.inventory.management.inventory.product;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findByProductCode(String productCode);

    /**
     * Atomic conditional decrement. Returns the resulting quantity, or empty if the WHERE
     * predicate excluded the row (i.e. insufficient stock) — RETURNING captures the
     * post-write value atomically so the caller never needs a separate racy re-read.
     *
     * Deliberately NOT annotated with @Modifying: Spring Data JPA's @Modifying only supports
     * void/int/long return types (it calls executeUpdate()), which can't carry the RETURNING
     * projection back. Omitting it makes Spring Data treat this as an ordinary native query
     * read via getResultList(), which works because the RETURNING clause makes Postgres hand
     * back a result set even though the statement itself is a write.
     */
    @Query(value = """
            UPDATE product SET quantity = quantity - :quantity, updated_at = now()
            WHERE id = :id AND quantity >= :quantity
            RETURNING quantity
            """, nativeQuery = true)
    Optional<Long> decreaseQuantityIfSufficient(@Param("id") Long id, @Param("quantity") Long quantity);

    @Query(value = """
            UPDATE product SET quantity = quantity + :quantity, updated_at = now()
            WHERE id = :id
            RETURNING quantity
            """, nativeQuery = true)
    Optional<Long> increaseQuantity(@Param("id") Long id, @Param("quantity") Long quantity);

    /**
     * Atomic upsert keyed on the product_code business key: registers a new product or
     * increments an existing one's quantity in a single statement, so concurrent
     * first-time registration of the same new product can't race.
     */
    @Query(value = """
            INSERT INTO product (product_code, name, quantity, created_at, updated_at)
            VALUES (:productCode, :name, :quantity, now(), now())
            ON CONFLICT (product_code) DO UPDATE
                SET quantity = product.quantity + EXCLUDED.quantity, updated_at = now()
            RETURNING id, quantity, (xmax = 0) AS inserted
            """, nativeQuery = true)
    UpsertResult upsertProductStock(@Param("productCode") String productCode,
                                     @Param("name") String name,
                                     @Param("quantity") Long quantity);

    interface UpsertResult {
        Long getId();

        Long getQuantity();

        Boolean getInserted();
    }
}

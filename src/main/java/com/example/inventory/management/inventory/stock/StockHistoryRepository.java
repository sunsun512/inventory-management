package com.example.inventory.management.inventory.stock;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockHistoryRepository extends JpaRepository<StockHistory, Long> {

    boolean existsByRequestId(String requestId);

    /**
     * Serializes all transactions that carry the same requestId: blocks until any other
     * transaction holding the lock for this key ends, and holds it until the calling transaction
     * ends (xact-level, released on commit/rollback). pg_advisory_xact_lock returns void, so it is
     * wrapped in a SELECT to give Spring Data a row to read. The session lock_timeout applies, so
     * a long wait fails with 55P03 like a row-lock wait. Must be called inside a transaction.
     */
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(hashtext(:requestId))", nativeQuery = true)
    Integer acquireRequestIdLock(@Param("requestId") String requestId);

    Page<StockHistory> findByProductId(Long productId, Pageable pageable);
}

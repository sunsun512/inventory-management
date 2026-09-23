package com.example.inventory.management.inventory.stock;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StockHistoryRepository extends JpaRepository<StockHistory, Long> {

    Optional<StockHistory> findByRequestId(String requestId);

    Page<StockHistory> findByProductId(Long productId, Pageable pageable);
}

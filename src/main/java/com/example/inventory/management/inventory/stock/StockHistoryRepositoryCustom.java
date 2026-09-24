package com.example.inventory.management.inventory.stock;

import com.example.inventory.management.inventory.stock.dto.StockHistoryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface StockHistoryRepositoryCustom {

    Page<StockHistoryResponse> findHistories(Long productId, Pageable pageable);
}

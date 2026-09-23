package com.example.inventory.management.inventory.stock.dto;

import com.example.inventory.management.inventory.stock.StockHistory;
import com.example.inventory.management.inventory.stock.StockType;

import java.time.Instant;

public record StockHistoryResponse(
        Long id,
        StockType type,
        Long quantity,
        Long beforeQuantity,
        Long afterQuantity,
        String requestId,
        Instant createdAt
) {

    public static StockHistoryResponse from(StockHistory history) {
        return new StockHistoryResponse(
                history.getId(),
                history.getType(),
                history.getQuantity(),
                history.getBeforeQuantity(),
                history.getAfterQuantity(),
                history.getRequestId(),
                history.getCreatedAt()
        );
    }
}

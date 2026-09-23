package com.example.inventory.management.inventory.stock;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "stock_history")
public class StockHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private StockType type;

    @Column(name = "quantity", nullable = false)
    private Long quantity;

    @Column(name = "before_quantity", nullable = false)
    private Long beforeQuantity;

    @Column(name = "after_quantity", nullable = false)
    private Long afterQuantity;

    @Column(name = "request_id", nullable = false, unique = true)
    private String requestId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected StockHistory() {
    }

    public StockHistory(Long productId, StockType type, Long quantity, Long beforeQuantity,
                         Long afterQuantity, String requestId) {
        this.productId = productId;
        this.type = type;
        this.quantity = quantity;
        this.beforeQuantity = beforeQuantity;
        this.afterQuantity = afterQuantity;
        this.requestId = requestId;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getProductId() {
        return productId;
    }

    public StockType getType() {
        return type;
    }

    public Long getQuantity() {
        return quantity;
    }

    public Long getBeforeQuantity() {
        return beforeQuantity;
    }

    public Long getAfterQuantity() {
        return afterQuantity;
    }

    public String getRequestId() {
        return requestId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

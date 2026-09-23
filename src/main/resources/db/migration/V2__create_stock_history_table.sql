CREATE TABLE stock_history (
    id               BIGSERIAL PRIMARY KEY,
    product_id       BIGINT       NOT NULL REFERENCES product (id),
    type             VARCHAR(16)  NOT NULL,
    quantity         BIGINT       NOT NULL,
    before_quantity  BIGINT       NOT NULL,
    after_quantity   BIGINT       NOT NULL,
    request_id       VARCHAR(64)  NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_stock_history_request_id UNIQUE (request_id),
    CONSTRAINT ck_stock_history_type CHECK (type IN ('INBOUND', 'OUTBOUND')),
    CONSTRAINT ck_stock_history_quantity_positive CHECK (quantity > 0),
    CONSTRAINT ck_stock_history_before_non_negative CHECK (before_quantity >= 0),
    CONSTRAINT ck_stock_history_after_non_negative CHECK (after_quantity >= 0)
);

CREATE INDEX idx_stock_history_product_id_created_at ON stock_history (product_id, created_at DESC);

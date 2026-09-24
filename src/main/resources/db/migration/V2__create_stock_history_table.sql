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

-- 재고 이력 조회는 product_id로 필터링하고 id DESC(실제 반영 순서)로 정렬·페이징한다.
-- 상품 row lock 덕분에 상품별 이력 id는 실제 반영 순서대로 증가하므로, 이 인덱스로 정렬 없이
-- 인덱스 순서 그대로 페이지를 읽을 수 있다.
CREATE INDEX idx_stock_history_product_id_id ON stock_history (product_id, id DESC);

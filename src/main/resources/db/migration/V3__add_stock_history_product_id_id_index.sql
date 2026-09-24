-- 재고 이력 조회는 product_id로 필터링하고 id DESC(실제 반영 순서)로 정렬·페이징한다.
-- 상품 row lock 덕분에 상품별 이력 id는 실제 반영 순서대로 증가하므로, 이 인덱스로 정렬 없이
-- 인덱스 순서 그대로 페이지를 읽을 수 있다. 기존 (product_id, created_at DESC) 인덱스는
-- 파괴적 변경을 피하기 위해 그대로 둔다.
CREATE INDEX idx_stock_history_product_id_id ON stock_history (product_id, id DESC);

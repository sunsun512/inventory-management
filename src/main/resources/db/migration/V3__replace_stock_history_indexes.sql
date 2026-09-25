-- CONCURRENTLY로 인덱스를 만들고 지워 stock_history 쓰기(입고·출고)를 막지 않는다.
-- Flyway가 CONCURRENTLY 구문을 인식해 이 파일 전체를 트랜잭션 없이 실행하므로, 이 파일에는 CONCURRENTLY 구문만 둔다.
-- 도중에 실패해도 롤백되지 않는다. INVALID로 남은 인덱스를 DROP INDEX CONCURRENTLY로 지우고
-- flyway repair 후 다시 실행한다. IF NOT EXISTS는 INVALID 인덱스를 그대로 건너뛰므로 쓰지 않는다.

-- 재고 이력 조회는 product_id로 필터링하고 created_at DESC, id DESC로 정렬·페이징한다.
-- 조건과 정렬 순서를 그대로 담은 인덱스 하나로 정렬 단계 없이 인덱스 순서대로 페이지를 읽는다.
CREATE INDEX CONCURRENTLY idx_stock_history_product_id_created_at_id ON stock_history (product_id, created_at DESC, id DESC);

-- 동점 정렬 키(id)가 없어 매 조회마다 Incremental Sort가 붙던 인덱스.
DROP INDEX CONCURRENTLY idx_stock_history_product_id_created_at;

-- 정렬 순서(id DESC)가 조회 쿼리와 맞지 않아 쓰이지 않고 쓰기 비용만 늘리던 인덱스.
DROP INDEX CONCURRENTLY idx_stock_history_product_id_id;

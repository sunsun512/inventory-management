-- 로컬 개발용 시드 데이터: 상품 10,000개 + 상품별 재고 요청 이력 8~12건(총 약 10만 건).
--
-- 애플리케이션 규칙을 그대로 따른다.
--   * product_code: ^[A-Z0-9]+$ (카테고리 접두어 + 6자리 일련번호, 예: FOOD000123)
--   * request_id: 소문자 UUID, 전역 유일
--   * 1회 요청 수량: 1 ~ 10,000 (QuantityLimit)
--   * 첫 이력은 신규 상품 등록 입고(before_quantity = 0)
--   * before/after가 끊김 없이 이어지고 재고는 음수가 되지 않으며,
--     product.quantity는 마지막 이력의 after_quantity와 같다.
--   * 이력은 created_at 순서로 INSERT하므로 상품별 id 순서 = 실제 반영 순서(V3 인덱스 전제).
--
-- Flyway 마이그레이션이 아니다. Spring Boot SQL 초기화(spring.sql.init)가 Flyway 마이그레이션 뒤에
-- 애플리케이션 기동마다 실행한다. local 프로필만 켜져 있고(mode: always, DB_SEED_MODE=never로 끔),
-- 공통 설정은 기본값(embedded)이라 Postgres를 쓰는 테스트·다른 환경에서는 실행되지 않는다.
--   * product / stock_history가 모두 비어 있을 때만 데이터를 넣는다. 데이터가 있으면 아무것도 지우거나
--     추가하지 않으므로 다시 기동해도 안전하다.
--   * spring.sql.init은 문장마다 자동 커밋하므로 스크립트 전체를 BEGIN/COMMIT으로 감싸 하나의 트랜잭션으로
--     실행한다(SET LOCAL, ON COMMIT DROP 임시 테이블, 검증 실패 시 전체 롤백이 이 전제에 의존한다).
--   * DO $$ 블록 안의 ;에서 문장이 잘리지 않도록 spring.sql.init.separator를 스크립트 끝 구분자로 두어
--     파일 전체를 한 번에 PgJDBC로 보낸다.

BEGIN;

-- 시드 생성·검증은 약 20초 걸려 공통 statement_timeout(기본 5s)을 넘으므로 이 트랜잭션에서만 해제한다.
SET LOCAL statement_timeout = 0;

-- 0) 두 테이블이 모두 비어 있을 때만 시드한다. 아니면 1)의 상품 원본이 0건이 되어 이후 단계가 모두 비게 된다.
CREATE TEMP TABLE seed_guard ON COMMIT DROP AS
SELECT NOT EXISTS (SELECT 1 FROM product) AND NOT EXISTS (SELECT 1 FROM stock_history) AS should_seed;

-- 같은 결과를 다시 얻을 수 있도록 난수 시드를 고정한다. 다른 데이터가 필요하면 값을 바꾼다.
SELECT setseed(0.20260924);

-- 1) 상품 원본: 카테고리/품목/옵션을 조합해 이름을 만들고, 이력 건수(8~12)와 시작 시각을 정한다.
CREATE TEMP TABLE seed_product ON COMMIT DROP AS
WITH category(ci, code, label, items) AS (
    VALUES
        (0, 'FOOD', '식품',   ARRAY['생수', '즉석밥', '라면', '커피믹스', '견과류', '올리브유', '참치캔', '그래놀라']),
        (1, 'ELEC', '전자',   ARRAY['무선이어폰', '보조배터리', 'USB-C 케이블', '블루투스 스피커', '스마트워치', '키보드', '마우스', '모니터암']),
        (2, 'HOME', '생활',   ARRAY['물티슈', '주방세제', '섬유유연제', '수건', '밀폐용기', '휴지', '방향제', '빨래건조대']),
        (3, 'BEAU', '뷰티',   ARRAY['선크림', '립밤', '핸드크림', '토너', '클렌징폼', '샴푸', '바디로션', '마스크팩']),
        (4, 'FASH', '패션',   ARRAY['양말', '반팔티', '후드집업', '청바지', '캡모자', '운동화', '백팩', '벨트']),
        (5, 'SPRT', '스포츠', ARRAY['요가매트', '덤벨', '줄넘기', '폼롤러', '등산스틱', '수영모', '텀블러', '헬스장갑']),
        (6, 'BOOK', '도서',   ARRAY['노트', '볼펜', '형광펜', '다이어리', '포스트잇', '파일철', '연필', '스케치북']),
        (7, 'TOYS', '완구',   ARRAY['블록세트', '퍼즐', '보드게임', '인형', 'RC카', '색점토', '비눗방울', '미니카'])
),
option_word(options) AS (
    VALUES (ARRAY['화이트', '블랙', '그레이', '네이비', '베이지', '대용량', '미니', '1+1', '리필용', '프리미엄'])
)
SELECT
    g                                                           AS n,
    c.code || lpad(g::text, 6, '0')                             AS product_code,
    '[' || c.label || '] '
        || c.items[1 + floor(random() * array_length(c.items, 1))::int] || ' '
        || o.options[1 + floor(random() * array_length(o.options, 1))::int] || ' '
        || (1 + floor(random() * 50))::int || '호'              AS name,
    (8 + floor(random() * 5))::int                              AS history_count,   -- 8~12건
    now() - interval '180 days' + random() * interval '140 days' AS registered_at    -- 180~40일 전
FROM generate_series(1, 10000) AS g
JOIN category c ON c.ci = g % 8
CROSS JOIN option_word o
WHERE (SELECT should_seed FROM seed_guard);

-- 2) 이력 체인: 재귀 CTE로 모든 상품의 n번째 요청을 한 단계씩 동시에 만든다.
--    재고가 0이면 반드시 입고, 그 외에는 입고 55% / 출고 45%.
--    출고는 보통 현재 재고의 최대 70%, 5% 확률로 재고를 전부 비워 품절 상품도 생기게 한다.
CREATE TEMP TABLE seed_history ON COMMIT DROP AS
WITH RECURSIVE chain AS (
    SELECT
        p.n,
        1                                             AS seq,
        'INBOUND'::varchar(16)                        AS type,
        (10 + floor(random() * 991))::bigint          AS quantity,          -- 첫 입고 10~1,000
        0::bigint                                     AS before_quantity,
        NULL::bigint                                  AS after_quantity,
        p.registered_at                               AS created_at
    FROM seed_product p

    UNION ALL

    SELECT
        c.n,
        c.seq + 1,
        d.type,
        d.quantity,
        c.before_quantity + CASE WHEN c.type = 'INBOUND' THEN c.quantity ELSE -c.quantity END,
        NULL,
        c.created_at + interval '10 minutes' + r.r_time * interval '3 days'
    FROM chain c
    JOIN seed_product p ON p.n = c.n AND c.seq < p.history_count
    CROSS JOIN LATERAL (
        -- c의 컬럼을 참조해야 행마다 난수가 새로 뽑힌다.
        SELECT
            c.before_quantity + CASE WHEN c.type = 'INBOUND' THEN c.quantity ELSE -c.quantity END AS stock,
            random() + 0 * c.seq AS r_type,
            random() + 0 * c.seq AS r_qty,
            random() + 0 * c.seq AS r_drain,
            random() + 0 * c.seq AS r_time
    ) r
    CROSS JOIN LATERAL (
        SELECT
            CASE WHEN r.stock = 0 OR r.r_type < 0.55 THEN 'INBOUND' ELSE 'OUTBOUND' END::varchar(16) AS type,
            CASE
                WHEN r.stock = 0 OR r.r_type < 0.55
                    -- 입고: 대부분 10~1,000, 3%는 대량 입고(최대 10,000)
                    THEN CASE WHEN r.r_drain < 0.03
                              THEN (1000 + floor(r.r_qty * 9001))::bigint
                              ELSE (10 + floor(r.r_qty * 991))::bigint END
                WHEN r.r_drain < 0.05 AND r.stock <= 10000
                    THEN r.stock                                                   -- 전량 출고(품절)
                ELSE greatest(1, floor(r.r_qty * least(r.stock, 10000) * 0.7))::bigint
            END AS quantity
    ) d
)
SELECT
    n,
    seq,
    type,
    quantity,
    before_quantity,
    before_quantity + CASE WHEN type = 'INBOUND' THEN quantity ELSE -quantity END AS after_quantity,
    created_at
FROM chain;

-- 3) 상품 INSERT: 등록 시각 순으로 넣어 id도 등록 순서를 따르게 한다.
INSERT INTO product (product_code, name, quantity, created_at, updated_at)
SELECT p.product_code, p.name, last.after_quantity, p.registered_at, last.created_at
FROM seed_product p
JOIN LATERAL (
    SELECT h.after_quantity, h.created_at
    FROM seed_history h
    WHERE h.n = p.n
    ORDER BY h.seq DESC
    LIMIT 1
) last ON true
ORDER BY p.registered_at, p.n;

-- 4) 이력 INSERT: 전체를 created_at 순으로 넣어 상품별 id 순서 = 반영 순서가 되게 한다.
INSERT INTO stock_history (product_id, type, quantity, before_quantity, after_quantity, request_id, created_at)
SELECT pr.id, h.type, h.quantity, h.before_quantity, h.after_quantity, gen_random_uuid()::text, h.created_at
FROM seed_history h
JOIN seed_product p ON p.n = h.n
JOIN product pr ON pr.product_code = p.product_code
ORDER BY h.created_at, h.n, h.seq;

-- 5) 검증: 하나라도 어긋나면 예외로 전체 롤백한다.
DO $$
DECLARE
    v_products     bigint;
    v_histories    bigint;
    v_broken_chain bigint;
    v_qty_mismatch bigint;
    v_bad_order    bigint;
    v_bad_first    bigint;
BEGIN
    IF NOT (SELECT should_seed FROM seed_guard) THEN
        RAISE NOTICE 'seed 건너뜀: 기존 상품/재고 이력 데이터가 있음';
        RETURN;
    END IF;

    SELECT count(*) INTO v_products FROM product;
    SELECT count(*) INTO v_histories FROM stock_history;

    -- 직전 이력의 after와 다음 이력의 before가 다르거나, 계산이 맞지 않는 행
    SELECT count(*) INTO v_broken_chain
    FROM (
        SELECT before_quantity, after_quantity, type, quantity,
               lag(after_quantity) OVER (PARTITION BY product_id ORDER BY id) AS prev_after
        FROM stock_history
    ) t
    WHERE (prev_after IS NOT NULL AND prev_after <> before_quantity)
       OR after_quantity <> before_quantity + CASE WHEN type = 'INBOUND' THEN quantity ELSE -quantity END
       OR quantity > 10000;

    -- product.quantity와 마지막 이력의 after_quantity 불일치
    SELECT count(*) INTO v_qty_mismatch
    FROM product p
    JOIN LATERAL (SELECT after_quantity FROM stock_history h WHERE h.product_id = p.id ORDER BY id DESC LIMIT 1) l ON true
    WHERE p.quantity <> l.after_quantity;

    -- 상품별 id 순서와 created_at 순서가 어긋나는 행
    SELECT count(*) INTO v_bad_order
    FROM (
        SELECT created_at, lag(created_at) OVER (PARTITION BY product_id ORDER BY id) AS prev_at
        FROM stock_history
    ) t
    WHERE prev_at > created_at;

    -- 첫 이력이 신규 등록 입고(before 0)가 아닌 상품
    SELECT count(*) INTO v_bad_first
    FROM (
        SELECT DISTINCT ON (product_id) type, before_quantity
        FROM stock_history ORDER BY product_id, id
    ) f
    WHERE f.type <> 'INBOUND' OR f.before_quantity <> 0;

    IF v_products <> 10000 OR v_broken_chain > 0 OR v_qty_mismatch > 0 OR v_bad_order > 0 OR v_bad_first > 0 THEN
        RAISE EXCEPTION 'seed 검증 실패: products=%, broken_chain=%, qty_mismatch=%, bad_order=%, bad_first=%',
            v_products, v_broken_chain, v_qty_mismatch, v_bad_order, v_bad_first;
    END IF;

    RAISE NOTICE 'seed 완료: 상품 %건, 이력 %건', v_products, v_histories;
END $$;

ANALYZE product;
ANALYZE stock_history;

COMMIT;

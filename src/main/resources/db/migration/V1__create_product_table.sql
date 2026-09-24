CREATE TABLE product (
    id            BIGSERIAL PRIMARY KEY,
    product_code  VARCHAR(64)  NOT NULL,
    name          VARCHAR(255) NOT NULL,
    quantity      BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_product_product_code UNIQUE (product_code),
    CONSTRAINT ck_product_quantity_non_negative CHECK (quantity >= 0)
);

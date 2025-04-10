-- Таблица сервисов
CREATE TABLE delivery_service (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    url VARCHAR(500)
);

-- Таблица продуктов
CREATE TABLE product (
    id BIGSERIAL PRIMARY KEY ,
    name VARCHAR(255) NOT NULL,
    description TEXT
);

-- Таблица цен на продукты в сервисах
CREATE TABLE product_price (
    id BIGSERIAL PRIMARY KEY,
    product_id BIGINT NOT NULL,
    service_id BIGINT NOT NULL,
    price DOUBLE PRECISION NOT NULL,
    FOREIGN KEY (product_id) REFERENCES product(id) ON DELETE CASCADE,
    FOREIGN KEY (service_id) REFERENCES delivery_service(id) ON DELETE CASCADE,
    UNIQUE (product_id, service_id)
);

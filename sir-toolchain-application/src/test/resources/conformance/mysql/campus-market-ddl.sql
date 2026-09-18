-- Conformance fixture DDL for the Campus Market `goods` table.
--
-- This file is the schema of record for the conformance run: the generated
-- application is never allowed to issue CREATE TABLE, so the harness creates the
-- owned schema out of band and then applies exactly this DDL through the control
-- session, after selecting the catalog with the JDBC catalog API. No
-- `CREATE DATABASE`, no `DROP`, and no `USE` statement appears here.
--
-- The column names and types mirror the generated Goods entity mapping:
--   id        Int64 generated auto        -> BIGINT AUTO_INCREMENT
--   title     String where length(1, 100) -> VARCHAR(100) NOT NULL
--   price     Decimal where min(0.01)     -> DECIMAL(19, 2) NOT NULL
--   seller    Ref<User>                   -> seller_id BIGINT NOT NULL
--   status    GoodsStatus                 -> VARCHAR(32) NOT NULL
--
-- The seller reference is stored as the referenced entity's identity column
-- (`seller_id`), never under the actor attribute name, which is why no
-- actor-named seller column appears in this fixture.
--
-- Every scenario fixture that inserts a row sets both `price` and `seller_id` from its
-- input and its actor, which is why this fixture keeps them NOT NULL.
--
-- DECIMAL(19, 2) is deliberate: the scenario prices carry two decimal places
-- (59.90, 1.00) and a money column must not round them. The generated entity maps
-- Decimal to java.math.BigDecimal with no column definition of its own, so this
-- fixture is what fixes the precision for the run.

CREATE TABLE goods (
    id BIGINT NOT NULL AUTO_INCREMENT,
    title VARCHAR(100) NOT NULL,
    price DECIMAL(19, 2) NOT NULL,
    seller_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_goods_seller ON goods (seller_id);

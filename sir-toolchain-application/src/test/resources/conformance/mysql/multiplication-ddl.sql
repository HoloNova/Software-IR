-- TEST_FIXTURE_DDL: isolated conformance schema, not a product migration.
CREATE TABLE calculation (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, amount DECIMAL(19,2) NOT NULL, `count` INT NOT NULL, wide BIGINT NOT NULL);
CREATE TABLE product (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, price DECIMAL(21,4) NOT NULL, stock INT NOT NULL);
CREATE TABLE receipt (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, product_id BIGINT NOT NULL, total DECIMAL(19,2) NOT NULL, quantity INT NOT NULL, CONSTRAINT fixture_receipt_product FOREIGN KEY (product_id) REFERENCES product(id));
CREATE TABLE receipt_line (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, receipt_id BIGINT NOT NULL, quantity INT NOT NULL, unit_price DECIMAL(21,4) NOT NULL, CONSTRAINT fixture_line_receipt FOREIGN KEY (receipt_id) REFERENCES receipt(id));

-- TEST_FIXTURE_DDL: isolated owned conformance schema, not a product migration.
CREATE TABLE wallet (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, balance DECIMAL(19,2) NOT NULL DEFAULT 0.00, rebate DECIMAL(19,2) NULL);
CREATE TABLE versioned_wallet (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, balance DECIMAL(19,2) NOT NULL, rebate DECIMAL(19,2) NULL, version BIGINT NOT NULL DEFAULT 0);

CREATE TABLE measurement (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, owner BIGINT NOT NULL, amount DECIMAL(19,2) NULL, label VARCHAR(255) NOT NULL, removed TINYINT(1) NOT NULL DEFAULT 0);
CREATE TABLE measurement_tag (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, measurement_id BIGINT NOT NULL, name VARCHAR(255) NOT NULL, CONSTRAINT fixture_tag_measurement FOREIGN KEY (measurement_id) REFERENCES measurement(id));

-- G8 grouped reads: same generated Money application and isolated database.
CREATE TABLE summary_category (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, owner BIGINT NOT NULL, removed TINYINT(1) NOT NULL DEFAULT 0);
CREATE TABLE summary_entry (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, owner BIGINT NOT NULL, category_id BIGINT NOT NULL, amount DECIMAL(19,2) NULL, occurred_at DATETIME(6) NOT NULL, label VARCHAR(255) NOT NULL, removed TINYINT(1) NOT NULL DEFAULT 0, CONSTRAINT fixture_summary_category FOREIGN KEY (category_id) REFERENCES summary_category(id));

-- TEST_FIXTURE_DDL: isolated owned conformance schema, not a product migration.
CREATE TABLE wallet (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, balance DECIMAL(19,2) NOT NULL DEFAULT 0.00, rebate DECIMAL(19,2) NULL);
CREATE TABLE versioned_wallet (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, balance DECIMAL(19,2) NOT NULL, rebate DECIMAL(19,2) NULL, version BIGINT NOT NULL DEFAULT 0);

CREATE TABLE measurement (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, owner BIGINT NOT NULL, amount DECIMAL(19,2) NULL, label VARCHAR(255) NOT NULL, removed TINYINT(1) NOT NULL DEFAULT 0);
CREATE TABLE measurement_tag (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, measurement_id BIGINT NOT NULL, name VARCHAR(255) NOT NULL, CONSTRAINT fixture_tag_measurement FOREIGN KEY (measurement_id) REFERENCES measurement(id));

-- G8 grouped reads: same generated Money application and isolated database.
CREATE TABLE summary_category (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, owner BIGINT NOT NULL, removed TINYINT(1) NOT NULL DEFAULT 0);
CREATE TABLE summary_entry (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, owner BIGINT NOT NULL, category_id BIGINT NOT NULL, amount DECIMAL(19,2) NULL, occurred_at DATETIME(6) NOT NULL, label VARCHAR(255) NOT NULL, removed TINYINT(1) NOT NULL DEFAULT 0, CONSTRAINT fixture_summary_category FOREIGN KEY (category_id) REFERENCES summary_category(id));

-- G8 calendar: actual ledger entities and DualTokenLogin, inside the existing Money application only.
CREATE TABLE user (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, time_zone VARCHAR(64) NULL, name VARCHAR(100) NOT NULL UNIQUE, password_hash VARCHAR(255) NOT NULL);
CREATE TABLE access_token (id CHAR(36) NOT NULL PRIMARY KEY, owner_id BIGINT NOT NULL, expires_at DATETIME(6) NOT NULL, CONSTRAINT fixture_calendar_access_owner FOREIGN KEY (owner_id) REFERENCES user(id));
CREATE TABLE refresh_token (id CHAR(36) NOT NULL PRIMARY KEY, owner_id BIGINT NOT NULL, used TINYINT(1) NOT NULL DEFAULT 0, expires_at DATETIME(6) NOT NULL, CONSTRAINT fixture_calendar_refresh_owner FOREIGN KEY (owner_id) REFERENCES user(id));
CREATE TABLE token_pair (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, access_token CHAR(36) NOT NULL, access_expires_at DATETIME(6) NOT NULL, refresh_token CHAR(36) NOT NULL, refresh_expires_at DATETIME(6) NOT NULL);
CREATE TABLE category (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, owner_id BIGINT NOT NULL, name VARCHAR(100) NOT NULL, direction VARCHAR(32) NOT NULL, CONSTRAINT fixture_calendar_category_owner FOREIGN KEY (owner_id) REFERENCES user(id));
CREATE TABLE entry (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, owner_id BIGINT NOT NULL, category_id BIGINT NOT NULL, amount DECIMAL(19,2) NOT NULL, note VARCHAR(500) NULL, occurred_at DATETIME(6) NOT NULL, CONSTRAINT fixture_calendar_entry_owner FOREIGN KEY (owner_id) REFERENCES user(id), CONSTRAINT fixture_calendar_entry_category FOREIGN KEY (category_id) REFERENCES category(id));
CREATE TABLE budget (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, owner_id BIGINT NOT NULL, category_id BIGINT NOT NULL, monthly_limit DECIMAL(19,2) NOT NULL, CONSTRAINT fixture_calendar_budget_owner FOREIGN KEY (owner_id) REFERENCES user(id), CONSTRAINT fixture_calendar_budget_category FOREIGN KEY (category_id) REFERENCES category(id));

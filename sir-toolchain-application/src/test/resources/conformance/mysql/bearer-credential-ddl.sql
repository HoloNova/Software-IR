-- Conformance fixture DDL for the bearer-credential slice (login issues a token, a Bearer header authenticates).
--
-- TEST_FIXTURE_DDL, not a product capability: the harness owns the schema and every assertion is read back
-- over an independent JDBC connection. access_token.id is CHAR(36) because a Uuid is stored as text.

CREATE TABLE user (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(64) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_user_name (name)
);

CREATE TABLE access_token (
    id CHAR(36) NOT NULL,
    owner_id BIGINT NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE note (
    id BIGINT NOT NULL AUTO_INCREMENT,
    author_id BIGINT NOT NULL,
    text VARCHAR(255) NOT NULL,
    PRIMARY KEY (id)
);

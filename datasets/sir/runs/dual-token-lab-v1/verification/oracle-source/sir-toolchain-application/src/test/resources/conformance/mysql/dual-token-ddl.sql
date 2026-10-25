-- Conformance fixture DDL for the dual-token scaffold slice (the output of `kcg scaffold dual-token`).
--
-- TEST_FIXTURE_DDL, not a product capability: the harness owns the schema and every assertion is read back over an
-- independent JDBC connection. Uuid keys are CHAR(36) because a Uuid is stored as its canonical text. user.name is
-- unique because the login looks an account up by it.

CREATE TABLE user (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(64) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_user_name (name)
);

CREATE TABLE note (
    id BIGINT NOT NULL AUTO_INCREMENT,
    author_id BIGINT NOT NULL,
    text VARCHAR(255) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE access_token (
    id CHAR(36) NOT NULL,
    owner_id BIGINT NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE refresh_token (
    id CHAR(36) NOT NULL,
    owner_id BIGINT NOT NULL,
    used TINYINT(1) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE token_pair (
    id BIGINT NOT NULL AUTO_INCREMENT,
    access_token CHAR(36) NOT NULL,
    access_expires_at DATETIME(6) NOT NULL,
    refresh_token CHAR(36) NOT NULL,
    refresh_expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
);

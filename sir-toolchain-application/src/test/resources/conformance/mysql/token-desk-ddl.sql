-- Conformance fixture DDL for the token-desk slice (Uuid keys, no-op guarded update, unique lookup, secret field).
--
-- TEST_FIXTURE_DDL, not a product capability. Two choices are deliberate:
--   * credential.id is CHAR(36): a Uuid is stored as its canonical text.
--   * account.name has NO unique index, although the SIR declares the field unique. The language does
--     not create indexes; leaving it out is what lets the seed contain two rows with one name, which is
--     the only way to observe what a keyed load does when the database breaks the declared uniqueness.

CREATE TABLE credential (
    id CHAR(36) NOT NULL,
    owner VARCHAR(64) NOT NULL,
    used TINYINT(1) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE account (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(64) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    PRIMARY KEY (id)
);

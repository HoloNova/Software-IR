-- Conformance fixture DDL for the guarded-transition slice (credential rotation).
--
-- TEST_FIXTURE_DDL, not a product capability: the generated application has no INITIALIZE/UPDATE
-- support, so the harness owns the schema and its rows, and every assertion is read back over an
-- independent JDBC connection.
--
-- `used` is the state the guarded update tests and flips. There is deliberately no version column:
-- the guard, not a version, is what keeps two requests from both rotating the same credential.

CREATE TABLE credential (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner VARCHAR(64) NOT NULL,
    used TINYINT(1) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
);

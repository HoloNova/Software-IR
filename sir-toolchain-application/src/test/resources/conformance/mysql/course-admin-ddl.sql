-- Conformance fixture DDL for the Course Admin write slice (Q10).
--
-- This schema is TEST_FIXTURE_DDL, not a product capability: the generated
-- application has no INITIALIZE/UPDATE support (those belong to G3), so the
-- harness owns the schema and its rows, and every assertion is read back over an
-- independent JDBC connection.
--
-- `version` is the concurrency token the SIR entity declares as `versioned`. It is
-- NOT NULL with a default, which is what lets the first change on a row be matched
-- against the value a fresh insert started at.

CREATE TABLE course (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500) NULL,
    capacity INT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
);

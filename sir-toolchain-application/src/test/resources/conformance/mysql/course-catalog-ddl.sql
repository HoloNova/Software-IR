-- Conformance fixture DDL for the Course Catalog `course` table (Q9).
--
-- This file is the schema of record for the query-slice conformance run. The
-- generated application never issues CREATE TABLE: the harness creates the owned
-- schema out of band and then applies exactly this DDL through the control
-- session after selecting the catalog. No `CREATE DATABASE`, no `DROP`, and no
-- `USE` statement appears here.
--
-- The column names and types mirror the generated Course entity mapping:
--   id          Int64 generated auto        -> BIGINT NOT NULL AUTO_INCREMENT
--   code        String where length(1, 32)  -> VARCHAR(32) NOT NULL
--   name        String where length(1, 100) -> VARCHAR(100) NOT NULL
--   description Optional<String> length(0, 500) -> VARCHAR(500) NULL
--   capacity    Int32 where min(1)          -> INT NOT NULL
--
-- `name` is the column the SearchCourses capability matches with a literal
-- `containsLiteral` predicate, so it is NOT NULL and VARCHAR(100) to match the
-- declared length constraint.
--
-- `description` is deliberately nullable and deliberately not part of the
-- CourseSummary projection. A response that returned the entity instead of the
-- projection would expose it, so the fixture keeps the column populated and the
-- projection test can prove the field is absent from the HTTP response.

CREATE TABLE course (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500) NULL,
    capacity INT NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_course_name ON course (name);

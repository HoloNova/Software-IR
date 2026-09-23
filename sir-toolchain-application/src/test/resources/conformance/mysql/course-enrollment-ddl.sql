-- Conformance fixture DDL for the Course Enrollment relation slice (Q11).
--
-- This file is the schema of record for the relation-slice conformance run. The
-- generated application never issues CREATE TABLE: the harness creates the owned
-- schema out of band and then applies exactly this DDL through the control
-- session after selecting the catalog. No `CREATE DATABASE`, no `DROP`, and no
-- `USE` statement appears here.
--
-- The column names and types mirror the generated entity mappings:
--   Course.id          Int64 generated auto -> BIGINT NOT NULL AUTO_INCREMENT
--   Course.code        String length(1, 32) -> VARCHAR(32) NOT NULL
--   Course.name        String length(1,100) -> VARCHAR(100) NOT NULL
--   Course.capacity    Int32 where min(1)   -> INT NOT NULL
--   Student.id         Int64 generated auto -> BIGINT NOT NULL AUTO_INCREMENT
--   Student.studentNo  String length(1, 16) -> VARCHAR(16) NOT NULL
--   Student.name       String length(1,100) -> VARCHAR(100) NOT NULL
--   Enrollment.id      Int64 generated auto -> BIGINT NOT NULL AUTO_INCREMENT
--   Enrollment.course  Ref<Course>          -> course_id BIGINT NOT NULL
--   Enrollment.student Ref<Student>         -> student_id BIGINT NOT NULL
--   Enrollment.status  EnrollmentStatus     -> VARCHAR(32) NOT NULL
--
-- The foreign keys are real constraints on purpose: the batch reads join on
-- `course_id` and `student_id`, so a read that used the wrong column would either
-- match nothing or fail here, and the environment is stricter than the generated
-- application assumes.
--
-- `idx_enrollment_course` indexes the column the correlated `EXISTS` and the
-- collection batch read both filter on, so the assertions measure the number of
-- statements rather than an accidental table scan.

CREATE TABLE student (
    id BIGINT NOT NULL AUTO_INCREMENT,
    student_no VARCHAR(16) NOT NULL,
    name VARCHAR(100) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE course (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(100) NOT NULL,
    capacity INT NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE enrollment (
    id BIGINT NOT NULL AUTO_INCREMENT,
    course_id BIGINT NOT NULL,
    student_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_enrollment_course FOREIGN KEY (course_id) REFERENCES course (id),
    CONSTRAINT fk_enrollment_student FOREIGN KEY (student_id) REFERENCES student (id)
);

CREATE INDEX idx_enrollment_course ON enrollment (course_id);
CREATE INDEX idx_enrollment_student ON enrollment (student_id);

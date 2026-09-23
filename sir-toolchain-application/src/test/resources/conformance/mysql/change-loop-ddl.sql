-- Conformance fixture DDL for the G1 change-loop slice (Q13).
--
-- This schema is TEST_FIXTURE_DDL, not a product capability: the generated
-- application has no INITIALIZE/UPDATE support, so the harness owns the schema and
-- every assertion reads it back over an independent JDBC connection.
--
-- The shape merges the two accepted slices the change loop starts from:
--   * Q10's write slice: `course` carries `description` (nullable, so a PATCH that
--     clears it can be told apart from one that leaves it alone) and `version`, the
--     concurrency token the entity declares as `versioned`.
--   * Q11's relation slice: `student` and `enrollment`, with real foreign keys on
--     `course_id`/`student_id` so a read that joined on the wrong column would match
--     nothing or fail here.
--
-- The column names and types mirror the generated entity mappings exactly:
--   Course.id          Int64 generated auto -> BIGINT NOT NULL AUTO_INCREMENT
--   Course.code        String length(1,32)  -> VARCHAR(32) NOT NULL
--   Course.name        String length(1,100) -> VARCHAR(100) NOT NULL
--   Course.description Optional<String>     -> VARCHAR(500) NULL
--   Course.capacity    Int32 where min(1)   -> INT NOT NULL
--   Course.version     Int64 versioned      -> BIGINT NOT NULL DEFAULT 0
--   Student.id/studentNo/name               -> BIGINT / VARCHAR(16) / VARCHAR(100)
--   Enrollment.course/student               -> course_id / student_id BIGINT NOT NULL
--   Enrollment.status  EnrollmentStatus     -> VARCHAR(32) NOT NULL
--
-- The one column name that matters for the change rounds is `course.name`: it is
-- VARCHAR(100) because the entity stays at length(1,100) — Q13's R2 tightens the
-- *input* constraint to 20, which is a request-level bound, not a schema change.

CREATE TABLE course (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500) NULL,
    capacity INT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
);

CREATE TABLE student (
    id BIGINT NOT NULL AUTO_INCREMENT,
    student_no VARCHAR(16) NOT NULL,
    name VARCHAR(100) NOT NULL,
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

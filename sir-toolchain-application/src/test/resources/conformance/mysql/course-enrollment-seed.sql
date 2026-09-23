-- Conformance fixture seed for the Course Enrollment relation slice (Q11).
--
-- This seed is TEST_FIXTURE_SEED, not product data: the fixture declares a single
-- read-only capability, so the harness owns every row here and asserts against
-- exactly these rows.
--
-- The rows are chosen so that each relation assertion is decisive rather than
-- incidentally true:
--
--   * Course 1 has two ACTIVE enrollments. A join-based read would serve it once
--     per matching enrollment, so the served page would hold four records instead
--     of three and the total would be four instead of three.
--   * Course 2 has one ACTIVE and one CANCELLED enrollment. It is selected as a
--     root because of the ACTIVE one, and its nested projection still serves both
--     rows: filtering the root and filtering the projection are different things.
--   * Course 3 has only a CANCELLED enrollment. It must not appear at all, which
--     only the existence semantics can explain: a join would serve it.
--   * Course 5 has one ACTIVE enrollment, so the page holds three roots and every
--     root has related rows. That is the shape in which a per-row read (N+1) would
--     be visible in the statement count.
--   * Course 4 has no enrollments at all, so "has related rows" is tested
--     separately from "has an ACTIVE related row".
--   * Three students back four nested enrollment rows, so the student batch read
--     cannot be one statement per nested row either.
--
--   * No single row satisfies both parts of the predicate for course 6: its only
--     enrollment is cancelled, while the active rows belong to other courses. An
--     implementation that turned one existence predicate into two independent ones
--     ("this course has enrollments" and "some enrollment is active") would serve
--     course 6, so its absence is what separates the two readings.
--   * Course 7 has thirty active enrollments. The page must read them with the
--     same number of statements as it uses for a course with one, which is what
--     makes the read count a statement count rather than a row count.
--
-- Expected result for page=1 size=10 (ordered by code): total 4, records
-- CS101 (enrollments 1,2), CS102 (enrollments 3,4), MAT101 (enrollment 6),
-- ZOO101 (enrollments 8..37).

INSERT INTO course (id, code, name, capacity) VALUES
    (1, 'CS101', 'Intro to Algorithms', 30),
    (2, 'CS102', 'Intro to Patterns', 20),
    (3, 'ART101', 'Art of Painting', 15),
    (4, 'PHY101', 'Intro to Physics', 50),
    (5, 'MAT101', 'Linear Algebra', 25),
    (6, 'ENG101', 'Technical Writing', 40),
    (7, 'ZOO101', 'Vertebrate Zoology', 12);

INSERT INTO student (id, student_no, name) VALUES
    (1, 'S001', 'Alice'),
    (2, 'S002', 'Bob'),
    (3, 'S003', 'Cara');

INSERT INTO enrollment (id, course_id, student_id, status) VALUES
    (1, 1, 1, 'ACTIVE'),
    (2, 1, 2, 'ACTIVE'),
    (3, 2, 1, 'ACTIVE'),
    (4, 2, 3, 'CANCELLED'),
    (5, 3, 2, 'CANCELLED'),
    (6, 5, 3, 'ACTIVE'),
    (7, 6, 1, 'CANCELLED'),
    (8, 7, 1, 'ACTIVE'),
    (9, 7, 2, 'ACTIVE'),
    (10, 7, 3, 'ACTIVE'),
    (11, 7, 1, 'ACTIVE'),
    (12, 7, 2, 'ACTIVE'),
    (13, 7, 3, 'ACTIVE'),
    (14, 7, 1, 'ACTIVE'),
    (15, 7, 2, 'ACTIVE'),
    (16, 7, 3, 'ACTIVE'),
    (17, 7, 1, 'ACTIVE'),
    (18, 7, 2, 'ACTIVE'),
    (19, 7, 3, 'ACTIVE'),
    (20, 7, 1, 'ACTIVE'),
    (21, 7, 2, 'ACTIVE'),
    (22, 7, 3, 'ACTIVE'),
    (23, 7, 1, 'ACTIVE'),
    (24, 7, 2, 'ACTIVE'),
    (25, 7, 3, 'ACTIVE'),
    (26, 7, 1, 'ACTIVE'),
    (27, 7, 2, 'ACTIVE'),
    (28, 7, 3, 'ACTIVE'),
    (29, 7, 1, 'ACTIVE'),
    (30, 7, 2, 'ACTIVE'),
    (31, 7, 3, 'ACTIVE'),
    (32, 7, 1, 'ACTIVE'),
    (33, 7, 2, 'ACTIVE'),
    (34, 7, 3, 'ACTIVE'),
    (35, 7, 1, 'ACTIVE'),
    (36, 7, 2, 'ACTIVE'),
    (37, 7, 3, 'ACTIVE');

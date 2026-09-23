-- Conformance fixture seed for the G1 change-loop slice (Q13).
--
-- TEST_FIXTURE_SEED, not product data. Every row is chosen so that a round's
-- assertion distinguishes the delivered behaviour from a plausible wrong one:
--
--   * Course 3 (ART101) has only a CANCELLED enrollment. Before R1 the search
--     requires an ACTIVE enrollment, so ART101 is absent; after R1 widens the filter
--     to "has any enrollment", ART101 appears. That single row is what makes "the
--     change reached the running service" a decisive assertion rather than a
--     restatement of the compile step.
--   * Course 4 (PHY101) has no enrollments at all, so it stays absent in both
--     readings: the change must not turn "any enrollment" into "any course".
--   * Course 1 (CS101) has two enrollments, so a join-based read would serve it twice
--     and raise the total; course 2 (CS102) has one ACTIVE and one CANCELLED
--     enrollment, so a root filter that also filtered its projection would lose a row.
--   * Course 3 carries version 4, like Q10's fixture: a conditional update that used a
--     constant instead of the stored version would be accepted when it must be refused.
--   * Course 5 (MAT101) has an ACTIVE enrollment and a NULL description, so a PATCH
--     that clears a description and one that leaves it alone are different outcomes.
--
-- Expected search totals (ordered by code):
--   before R1 (ACTIVE only): CS101, CS102, MAT101  -> total 3
--   after  R1 (any enrollment): CS101, CS102, ART101, MAT101 -> total 4

INSERT INTO course (id, code, name, description, capacity, version) VALUES
    (1, 'CS101', 'Intro to Algorithms', 'Asymptotic analysis and recursion', 30, 0),
    (2, 'CS102', 'Intro to Patterns', NULL, 20, 0),
    (3, 'ART101', 'Art of Painting', 'Studio course on colour mixing', 15, 4),
    (4, 'PHY101', 'Intro to Physics', 'Mechanics and waves', 50, 0),
    (5, 'MAT101', 'Linear Algebra', 'Vector spaces', 25, 0);

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
    (6, 5, 3, 'ACTIVE');

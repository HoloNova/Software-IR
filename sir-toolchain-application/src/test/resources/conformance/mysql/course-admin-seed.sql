-- Conformance fixture seed for the Course Admin write slice (Q10).
--
-- TEST_FIXTURE_SEED, not product data. The rows are chosen so that each assertion
-- distinguishes the behaviour under test from a plausible wrong implementation:
--
--   * Row 1 carries a non-null description, so clearing it (PATCH with an explicit
--     null change) can be told apart from leaving it alone.
--   * Row 2 carries a NULL description and a distinct name, so a subset change on
--     row 1 cannot be satisfied by looking at row 2, and the "leave it alone" case
--     has a row whose value is already null.
--   * Row 3 carries version 4, not 0. A change that assumed a fresh row's version
--     would be accepted when it should be refused, so the stale-version assertion
--     is decisive about using the stored version rather than a constant.

INSERT INTO course (id, code, name, description, capacity, version) VALUES
    (1, 'CS101', 'Intro to Algorithms', 'Asymptotic analysis and recursion', 30, 0),
    (2, 'CS102', 'Intro to Patterns', NULL, 20, 0),
    (3, 'ART101', 'Art of Painting', 'Studio course on colour mixing', 15, 4);

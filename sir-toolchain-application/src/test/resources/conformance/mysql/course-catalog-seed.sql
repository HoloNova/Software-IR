-- Conformance fixture seed for the Course Catalog query slice (Q9).
--
-- This seed is TEST_FIXTURE_SEED, not product data: the generated application
-- has no INITIALIZE/UPDATE capability (those belong to G3), so the harness owns
-- every row here and asserts against exactly these rows.
--
-- The rows are chosen so that the literal-match, paging, and ordering assertions
-- are each decisive rather than incidentally true:
--
--   * Four rows contain the plain word "Intro", which gives the paging
--     assertions a set larger than one page (total 4, page size 2 -> 2 pages).
--   * Two rows contain a literal `%` in `name`. If the generated query did not
--     escape the literal, the keyword `%` would build the SQL pattern `%%%`,
--     which matches every row in the table; the assertion expects exactly 2, so
--     a non-escaping implementation fails.
--   * Two rows contain a literal `_` (as `9_9`) and one row contains `919`.
--     Unescaped, `_` is a single-character wildcard, so the keyword `9_9` would
--     also match `919` (3 rows instead of 2).
--   * Every course name is distinct and every code sorts differently from its
--     insertion order, so an unordered (primary-key ordered) result cannot
--     accidentally satisfy the `order by code ascending, id ascending`
--     assertion.
--
-- `description` is populated for every row and is never projected into
-- CourseSummary, so its absence from the response body is meaningful.

INSERT INTO course (id, code, name, description, capacity) VALUES
    (1, 'CS103', 'Intro to Databases', 'Relational modelling and normal forms', 40),
    (2, 'ART101', 'Art of 100% Painting', 'Studio course on colour mixing', 15),
    (3, 'CS101', 'Intro to Algorithms 100%', 'Asymptotic analysis and recursion', 30),
    (4, 'MATH201', 'Math 9_9 Advanced', 'Sequences, series and limits', 35),
    (5, 'MATH202', 'Math 919 Applied', 'Applied numerical methods', 25),
    (6, 'CS102', 'Intro to 9_9 Patterns', 'Design patterns in practice', 20),
    (7, 'CS104', 'Intro to Compilers', 'Lexing, parsing and code generation', 45);

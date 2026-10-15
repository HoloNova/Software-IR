-- Conformance fixture seed for the guarded-transition slice (credential rotation).
--
-- TEST_FIXTURE_SEED, not product data. Each row separates the behaviour under test from a plausible
-- wrong implementation:
--
--   * Row 1 is eligible: not used, expires far in the future. The racing requests all target it, so
--     exactly one can win and the others can only be refused by the guard (the row is loaded as
--     eligible by every one of them).
--   * Row 2 was already used and has not expired, so a refusal can only come from the guard and not
--     from the expiry check.
--   * Row 3 is unused but expired in the past, so a refusal can only come from the expiry check and
--     not from the guard.

INSERT INTO credential (id, owner, used, expires_at) VALUES
    (1, 'alice', 0, '2099-01-01 00:00:00.000000'),
    (2, 'bob', 1, '2099-01-01 00:00:00.000000'),
    (3, 'carol', 0, '2000-01-01 00:00:00.000000');

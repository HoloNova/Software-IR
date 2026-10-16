-- Conformance fixture seed for the token-desk slice. TEST_FIXTURE_SEED, not product data.
--
--   * credential 1111...: eligible, so a refresh rotates it.
--   * credential 2222...: owned by alice and already used. Touching it sets used to 1, the value it
--     already has: the update changes nothing and must still count as a match, not as a refused guard.
--   * credential 3333...: owned by bob, so the guard of Touch refuses it.
--   * accounts: one unique name, and the name 'dup' twice (see the DDL). Their stored password text is not a
--     digest, so no candidate matches it (a stored text in any other format is a mismatch, not an error).

INSERT INTO credential (id, owner, used, expires_at) VALUES
    ('11111111-1111-4111-8111-111111111111', 'alice', 0, '2099-01-01 00:00:00.000000'),
    ('22222222-2222-4222-8222-222222222222', 'alice', 1, '2099-01-01 00:00:00.000000'),
    ('33333333-3333-4333-8333-333333333333', 'bob', 0, '2099-01-01 00:00:00.000000');

INSERT INTO account (id, name, password_hash) VALUES
    (1, 'ann', 'not-a-digest'),
    (2, 'dup', 'not-a-digest'),
    (3, 'dup', 'not-a-digest');

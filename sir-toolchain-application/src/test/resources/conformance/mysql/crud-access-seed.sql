-- TEST_FIXTURE_SEED: deterministic credentials, only for this isolated CI schema.
INSERT INTO user (id, role) VALUES (1, 'ADMIN'), (2, 'READER'), (3, 'ADMIN');
INSERT INTO access_token (id, owner_id, expires_at) VALUES
('00000000-0000-4000-8000-000000000001', 1, '2099-01-01'),
('00000000-0000-4000-8000-000000000002', 2, '2099-01-01'),
('00000000-0000-4000-8000-000000000003', 3, '2099-01-01');
INSERT INTO book (id, title, stock) VALUES (1, 'seed book', 5);
INSERT INTO post (id, author_id, body, state, version) VALUES (1, 1, 'visible', 'open', 1), (2, 1, 'private', 'hidden', 1);

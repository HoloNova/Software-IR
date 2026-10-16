-- Conformance fixture seed for the dual-token scaffold slice. TEST_FIXTURE_SEED, not product data.
--
-- One account whose stored password text is not a digest, so no password logs in as it; it exists so that the
-- readiness route has a row to answer. Every other account and token in the run is created through the application.

INSERT INTO user (id, name, password_hash) VALUES (1, 'seed', 'not-a-digest');

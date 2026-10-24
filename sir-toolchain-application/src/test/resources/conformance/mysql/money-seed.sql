-- TEST_FIXTURE_SEED: boundary rows are set explicitly by the conformance driver.
INSERT INTO wallet (id,balance,rebate) VALUES (1,10.00,NULL),(2,20.00,NULL);
INSERT INTO versioned_wallet (id,balance,rebate,version) VALUES (1,5.00,NULL,1);

INSERT INTO measurement (id,owner,amount,label) VALUES
(1,1,1.23,'a_%b'),(2,1,2.34,'axb'),(3,1,NULL,'null'),(4,1,-0.57,'negative'),
(5,2,999.99,'other-owner'),(6,3,NULL,'null-a'),(7,3,NULL,'null-b'),
(8,5,99999999999999999.99,'large-a'),(9,5,99999999999999999.99,'large-b');
INSERT INTO measurement_tag (id,measurement_id,name) VALUES (1,1,'target'),(2,1,'target'),(3,2,'target'),(4,5,'target');

INSERT INTO measurement (id,owner,amount,label,removed) VALUES (10,1,1234567.89,'hidden',1);

INSERT INTO summary_category(id,owner,removed) VALUES (101,1,0),(102,1,0),(103,1,0),(104,2,0),(105,1,1),(106,1,0),(901,99,0),(902,99,0);
INSERT INTO summary_entry(id,owner,category_id,amount,occurred_at,label,removed) VALUES
(1001,1,101,1.23,'2026-01-01 00:00:00','target',0),
(1002,1,101,2.34,'2026-01-10 00:00:00','target',0),
(1003,1,101,-0.57,'2026-01-20 00:00:00','target',0),
(1004,1,102,NULL,'2026-01-10 00:00:00','target',0),
(1005,1,103,99999999999999999.99,'2026-01-10 00:00:00','target',0),
(1006,1,103,99999999999999999.99,'2026-01-10 00:00:00','target',0),
(1007,1,104,999.99,'2026-01-10 00:00:00','target',0),
(1008,1,105,999.99,'2026-01-10 00:00:00','target',0),
(1009,2,101,999.99,'2026-01-10 00:00:00','target',0),
(1010,1,101,999.99,'2026-01-10 00:00:00','target',1),
(1011,1,101,999.99,'2026-02-01 00:00:00','target',0),
(1012,1,101,999.99,'2025-12-31 23:59:59','target',0),
(9001,99,901,1.00,'2026-01-10 00:00:00','snapshot',0);

-- Fixed owned calendar test identities; token validation uses the actual generated DualTokenLogin resolver.
INSERT INTO user (id,name,password_hash,time_zone) VALUES (1,'calendar-alice','not-a-digest','America/New_York'),(2,'calendar-bob','not-a-digest','UTC'),(3,'calendar-unset','not-a-digest',NULL);
INSERT INTO access_token (id,owner_id,expires_at) VALUES ('00000000-0000-4000-8000-000000000001',1,'2099-01-01'),('00000000-0000-4000-8000-000000000002',2,'2099-01-01'),('00000000-0000-4000-8000-000000000003',3,'2099-01-01');
INSERT INTO category (id,owner_id,name,direction) VALUES (10,1,'calendar-own','EXPENSE'),(20,2,'calendar-other','EXPENSE');
INSERT INTO budget (id,owner_id,category_id,monthly_limit) VALUES (10,1,10,30.00),(20,2,20,1.00),(30,1,20,1.00);
-- New York March UTC boundaries: 05:00Z on March 1, 04:00Z on April 1. Microsecond edges are deliberate.
INSERT INTO entry (id,owner_id,category_id,amount,occurred_at) VALUES
(8001,1,10,99.00,'2026-03-01 04:59:59.999999'),(8002,1,10,10.00,'2026-03-01 05:00:00.000000'),
(8003,1,10,20.00,'2026-04-01 03:59:59.999999'),(8004,1,10,99.00,'2026-04-01 04:00:00.000000'),
(8005,2,10,999.00,'2026-03-15 00:00:00'),(8006,1,20,999.00,'2026-03-15 00:00:00'),
(8007,1,10,1.00,'1000-01-01 00:00:00');

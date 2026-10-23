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

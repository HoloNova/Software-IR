-- TEST_FIXTURE_SEED: boundary rows are set explicitly by the conformance driver.
INSERT INTO wallet (id,balance,rebate) VALUES (1,10.00,NULL),(2,20.00,NULL);
INSERT INTO versioned_wallet (id,balance,rebate,version) VALUES (1,5.00,NULL,1);

INSERT INTO measurement (id,owner,amount,label) VALUES
(1,1,1.23,'a_%b'),(2,1,2.34,'axb'),(3,1,NULL,'null'),(4,1,-0.57,'negative'),
(5,2,999.99,'other-owner'),(6,3,NULL,'null-a'),(7,3,NULL,'null-b'),
(8,5,99999999999999999.99,'large-a'),(9,5,99999999999999999.99,'large-b');
INSERT INTO measurement_tag (id,measurement_id,name) VALUES (1,1,'target'),(2,1,'target'),(3,2,'target'),(4,5,'target');

INSERT INTO measurement (id,owner,amount,label,removed) VALUES (10,1,1234567.89,'hidden',1);

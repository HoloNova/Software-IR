-- TEST_FIXTURE_SEED: boundary rows are set explicitly by the conformance driver.
INSERT INTO wallet (id,balance,rebate) VALUES (1,10.00,NULL),(2,20.00,NULL);
INSERT INTO versioned_wallet (id,balance,rebate,version) VALUES (1,5.00,NULL,1);

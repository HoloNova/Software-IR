-- TEST_FIXTURE_SEED: exact price boundaries and explicit stock for the owned scenario.
INSERT INTO product (id,price,stock) VALUES (1,1.2300,20),(2,0.1250,10),(3,99999999999999999.9900,10),(4,0.1250,3),(5,0.1250,0);
INSERT INTO course (id,capacity,enrolled) VALUES (1,3,0),(2,1,1);

INSERT INTO room (id,name) VALUES (1,'Concurrent'),(2,'Independent'),(3,'WaitThenRead');

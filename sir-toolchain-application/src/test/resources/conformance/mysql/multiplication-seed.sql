-- TEST_FIXTURE_SEED: exact price boundaries and explicit stock for the owned scenario.
INSERT INTO product (id,price,stock) VALUES (1,1.2300,20),(2,0.1250,10),(3,99999999999999999.9900,10),(4,0.1250,3),(5,0.1250,0);
INSERT INTO course (id,capacity,enrolled) VALUES (1,3,0),(2,1,1);

INSERT INTO room (id,name) VALUES (1,'Concurrent'),(2,'Independent'),(3,'WaitThenRead');

INSERT INTO user (id,role,name,password_hash) VALUES(1,'CUSTOMER','cancel-owner','not-a-login-password'),(2,'CUSTOMER','foreign-buyer','not-a-login-password');
INSERT INTO access_token (id,owner_id,expires_at) VALUES('00000000-0000-4000-8000-000000000001',1,'2099-01-01 00:00:00'),('00000000-0000-4000-8000-000000000002',2,'2099-01-01 00:00:00');
INSERT INTO shop_product (id,name,price,stock,state) VALUES(1,'first',1.00,10,'OFF_SHELF'),(2,'second',2.00,20,'ON_SHELF'),(3,'purchasable',2.50,10,'ON_SHELF'),(4,'full',1.00,2147483647,'ON_SHELF');

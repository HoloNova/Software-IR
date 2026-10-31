INSERT INTO `user` (id,name) VALUES (1,'alice'),(2,'bob'),(3,'carol'),(4,'dave');
INSERT INTO access_token (id,owner_id,expires_at) VALUES
 ('40000000-0000-4000-8000-000000000001',1,'2099-01-01 00:00:00.000000'),
 ('40000000-0000-4000-8000-000000000002',2,'2099-01-01 00:00:00.000000'),
 ('40000000-0000-4000-8000-000000000003',3,'2099-01-01 00:00:00.000000'),
 ('40000000-0000-4000-8000-000000000004',4,'2099-01-01 00:00:00.000000'),
 ('40000000-0000-4000-8000-000000000099',1,'2000-01-01 00:00:00.000000');
INSERT INTO facility (id,name,manager_id) VALUES (10,'Boiler',1),(11,'Lift',2),(12,'Empty',1);
INSERT INTO work_order (id,facility_id,reporter_id,title,assignee,note,removed,version) VALUES
 (101,10,4,'Leak',NULL,NULL,0,0),
 (102,10,4,'Valve',3,'checked',0,2),
 (103,10,2,'Archived',3,'old',1,5),
 (104,11,1,'Button',2,NULL,0,0),
 (105,10,4,'Race',3,'race',0,0),
 (106,10,4,'Gauge',2,NULL,0,0);
INSERT INTO inspection_order (id,facility_id,title,due_at,closed) VALUES
 (201,10,'Inspect A','2000-01-01 00:00:00.000000',0),
 (202,10,'Inspect B','2000-01-01 00:00:00.000000',0),
 (203,10,'At cutoff','2000-01-02 00:00:00.000000',0),
 (204,10,'Already done','2000-01-01 00:00:00.000000',1),
 (205,11,'Other site','2000-01-01 00:00:00.000000',0),
 (206,10,'Future','2099-01-01 00:00:00.000000',0);

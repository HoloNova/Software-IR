-- TEST_FIXTURE_SEED: counts deliberately match active loans; no live data backfill is performed here.
INSERT INTO user (id,role,active_loans,name,password_hash) VALUES
(1,'READER',4,'four-loans','not-a-digest'),(2,'READER',0,'other','not-a-digest'),
(3,'READER',1,'return-errors','not-a-digest'),(4,'READER',0,'empty','not-a-digest'),
(5,'READER',1,'renew','not-a-digest'),(6,'READER',0,'competing-a','not-a-digest'),(7,'READER',0,'competing-b','not-a-digest');
INSERT INTO access_token (id,owner_id,expires_at) VALUES
('00000000-0000-4000-8000-000000000001',1,'2099-01-01'),('00000000-0000-4000-8000-000000000002',2,'2099-01-01'),
('00000000-0000-4000-8000-000000000003',3,'2099-01-01'),('00000000-0000-4000-8000-000000000004',4,'2099-01-01'),
('00000000-0000-4000-8000-000000000005',5,'2099-01-01'),('00000000-0000-4000-8000-000000000006',6,'2099-01-01'),
('00000000-0000-4000-8000-000000000007',7,'2099-01-01');
INSERT INTO book (id,isbn,title,author) VALUES (1,'ISBN1','Concurrency','Fixture');
INSERT INTO copy (id,barcode,book_id,status) VALUES
(1,'C1',1,'BORROWED'),(2,'C2',1,'BORROWED'),(3,'C3',1,'BORROWED'),(4,'C4',1,'BORROWED'),
(5,'C5',1,'AVAILABLE'),(6,'C6',1,'AVAILABLE'),(7,'C7',1,'AVAILABLE'),(8,'C8',1,'AVAILABLE'),
(9,'C9',1,'AVAILABLE'),(10,'C10',1,'AVAILABLE'),(11,'C11',1,'AVAILABLE'),(12,'C12',1,'AVAILABLE'),
(13,'C13',1,'AVAILABLE'),(20,'C20',1,'AVAILABLE'),(31,'C31',1,'BORROWED'),(51,'C51',1,'BORROWED'),(60,'C60',1,'AVAILABLE');
INSERT INTO loan (id,reader_id,copy_id,borrowed_at,due_at,renewals,returned) VALUES
(1,1,1,UTC_TIMESTAMP(6),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 29 DAY),0,0),
(2,1,2,UTC_TIMESTAMP(6),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 29 DAY),0,0),
(3,1,3,UTC_TIMESTAMP(6),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 29 DAY),0,0),
(4,1,4,UTC_TIMESTAMP(6),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 29 DAY),0,0),
(31,3,31,UTC_TIMESTAMP(6),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 29 DAY),0,0),
(51,5,51,UTC_TIMESTAMP(6),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 29 DAY),0,0);

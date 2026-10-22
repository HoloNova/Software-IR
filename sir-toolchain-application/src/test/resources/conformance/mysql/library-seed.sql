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

-- TEST_FIXTURE_SEED: separate books/readers keep queue checks independent of the original borrowing scenarios.
INSERT INTO user (id,role,active_loans,name,password_hash) VALUES
(10,'READER',0,'queue-a','not-a-digest'),(11,'READER',0,'queue-b','not-a-digest'),
(12,'READER',0,'queue-c','not-a-digest'),(13,'READER',0,'outsider','not-a-digest'),
(14,'READER',1,'first-holder','not-a-digest'),(15,'LIBRARIAN',0,'queue-staff','not-a-digest'),
(16,'READER',0,'multi-a','not-a-digest'),(17,'READER',0,'multi-b','not-a-digest'),
(18,'READER',0,'multi-c','not-a-digest'),(19,'READER',0,'independent','not-a-digest'),
(20,'READER',5,'full-head','not-a-digest'),(21,'READER',2,'multi-holder','not-a-digest'),
(22,'READER',1,'full-book-holder','not-a-digest'),(24,'READER',0,'waiting-follower','not-a-digest'),
(25,'READER',0,'cancelled-head','not-a-digest'),(26,'READER',0,'rollback-head','not-a-digest'),
(27,'READER',0,'duplicate-queue','not-a-digest'),(28,'READER',0,'claim-cancel-race','not-a-digest');
INSERT INTO access_token (id,owner_id,expires_at)
SELECT CONCAT('00000000-0000-4000-8000-',LPAD(id,12,'0')),id,'2099-01-01' FROM user WHERE id>=10;
INSERT INTO book (id,isbn,title,author) VALUES
(10,'ISBN10','FIFO','Fixture'),(11,'ISBN11','Multiple copies','Fixture'),(12,'ISBN12','Lost','Fixture'),
(13,'ISBN13','No copies','Fixture'),(14,'ISBN14','Full head','Fixture'),(15,'ISBN15','Independent A','Fixture'),
(16,'ISBN16','Independent B','Fixture'),(17,'ISBN17','Wait and cancel','Fixture'),
(18,'ISBN18','Late rollback','Fixture'),(19,'ISBN19','Claim cancel race','Fixture'),(20,'ISBN20','Full readers old loans','Fixture');
INSERT INTO copy (id,barcode,book_id,status) VALUES
(100,'C100',10,'BORROWED'),(110,'C110',11,'BORROWED'),(111,'C111',11,'BORROWED'),
(120,'C120',12,'LOST'),(140,'C140',14,'BORROWED'),(150,'C150',15,'AVAILABLE'),
(160,'C160',16,'AVAILABLE'),(170,'C170',17,'AVAILABLE'),(180,'C180',18,'AVAILABLE'),
(190,'C190',19,'AVAILABLE'),(200,'C200',20,'BORROWED'),(201,'C201',20,'BORROWED'),(202,'C202',20,'BORROWED'),
(203,'C203',20,'BORROWED'),(204,'C204',20,'BORROWED');
INSERT INTO loan (id,reader_id,copy_id,borrowed_at,due_at,renewals,returned) VALUES
(100,14,100,UTC_TIMESTAMP(6),'2099-01-01',0,0),
(110,21,110,UTC_TIMESTAMP(6),'2099-01-01',0,0),(111,21,111,UTC_TIMESTAMP(6),'2099-01-01',0,0),
(140,22,140,UTC_TIMESTAMP(6),'2099-01-01',0,0),
(200,20,200,UTC_TIMESTAMP(6),'2099-01-01',0,0),(201,20,201,UTC_TIMESTAMP(6),'2099-01-01',0,0),
(202,20,202,UTC_TIMESTAMP(6),'2099-01-01',0,0),(203,20,203,UTC_TIMESTAMP(6),'2099-01-01',0,0),
(204,20,204,UTC_TIMESTAMP(6),'2099-01-01',0,0);
INSERT INTO reservation (id,reader_id,book_id,queued_at) VALUES
(800,25,17,UTC_TIMESTAMP(6)),(801,24,17,UTC_TIMESTAMP(6)),(810,26,18,UTC_TIMESTAMP(6)),(820,28,19,UTC_TIMESTAMP(6));

-- TEST_FIXTURE_SEED: explicit schedules, trusted transcript facts, and counters matching enrollment rows.
INSERT INTO user (id,role,name,password_hash) VALUES
(1,'ADMIN','admin','not-a-digest'),(2,'TEACHER','teacher','not-a-digest'),
(10,'STUDENT','none','not-a-digest'),(11,'STUDENT','partial','not-a-digest'),(12,'STUDENT','passed','not-a-digest'),(13,'STUDENT','other-partial','not-a-digest'),
(14,'STUDENT','conflict-race','not-a-digest'),(15,'STUDENT','independent','not-a-digest'),(16,'STUDENT','lock-wait','not-a-digest'),
(22,'STUDENT','rollback','not-a-digest'),(24,'STUDENT','all-four','not-a-digest'),(25,'STUDENT','same-count-wrong-set','not-a-digest'),
(30,'STUDENT','seat30','not-a-digest'),(31,'STUDENT','seat31','not-a-digest'),(32,'STUDENT','seat32','not-a-digest'),(33,'STUDENT','seat33','not-a-digest'),
(34,'STUDENT','seat34','not-a-digest'),(35,'STUDENT','seat35','not-a-digest'),(36,'STUDENT','seat36','not-a-digest'),(37,'STUDENT','seat37','not-a-digest');
INSERT INTO access_token (id,owner_id,expires_at)
SELECT CONCAT('00000000-0000-4000-8000-',LPAD(id,12,'0')),id,'2099-01-01' FROM user;
INSERT INTO course (id,code,name,teacher_id,capacity,enrolled,weekday,start_period,end_period,published) VALUES
(1,'A','Prior A',2,20,0,5,1,2,1),(2,'B','Prior B',2,20,0,5,2,3,1),(3,'C','Prior C',2,20,0,5,3,4,1),(4,'D','Prior D',2,20,0,5,4,5,1),
(10,'TARGET','Needs A and B',2,10,0,1,1,3,1),(11,'ADJACENT','Touches target',2,10,0,1,3,5,1),(12,'OVERLAP','Overlaps target',2,10,0,1,2,4,1),
(13,'FOUR','Needs four courses',2,10,0,3,1,2,1),(14,'DRAFT','Unpublished',2,10,0,4,1,2,0),(15,'CONFIG','Configure then publish',2,10,0,4,4,5,0),
(21,'RACE-A','Concurrent A',2,10,0,2,1,4,1),(22,'RACE-B','Concurrent B',2,10,0,2,3,5,1),
(23,'DAY3','Independent day3',2,10,0,3,1,2,1),(24,'DAY4','Independent day4',2,10,0,4,1,2,1),
(30,'SEATS','Three seats',2,3,0,6,1,2,1),(40,'ROLLBACK','Late failure',2,10,1,7,1,2,1);
INSERT INTO prerequisite (target_course_id,required_id) VALUES (10,1),(10,2),(13,1),(13,2),(13,3),(13,4);
INSERT INTO completed_course (student_id,course_id,completed_at) VALUES
(11,1,UTC_TIMESTAMP(6)),(12,1,UTC_TIMESTAMP(6)),(12,2,UTC_TIMESTAMP(6)),(13,2,UTC_TIMESTAMP(6)),
(24,1,UTC_TIMESTAMP(6)),(24,2,UTC_TIMESTAMP(6)),(24,3,UTC_TIMESTAMP(6)),(24,4,UTC_TIMESTAMP(6)),
(25,1,UTC_TIMESTAMP(6)),(25,3,UTC_TIMESTAMP(6));
INSERT INTO enrollment (id,student_id,course_id,created_at) VALUES (400,25,40,UTC_TIMESTAMP(6));

-- TEST_FIXTURE_DDL: isolated owned schema, not a migration.
CREATE TABLE `user` (
  id BIGINT NOT NULL AUTO_INCREMENT,
  name VARCHAR(100) NOT NULL,
  PRIMARY KEY (id), UNIQUE KEY maintenance_user_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE access_token (
  id CHAR(36) NOT NULL, owner_id BIGINT NOT NULL, expires_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id), FOREIGN KEY (owner_id) REFERENCES `user` (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE facility (
  id BIGINT NOT NULL AUTO_INCREMENT, name VARCHAR(100) NOT NULL, manager_id BIGINT NOT NULL,
  PRIMARY KEY (id), FOREIGN KEY (manager_id) REFERENCES `user` (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE work_order (
  id BIGINT NOT NULL AUTO_INCREMENT, facility_id BIGINT NOT NULL, reporter_id BIGINT NOT NULL,
  title VARCHAR(100) NOT NULL, assignee BIGINT NULL, note VARCHAR(500) NULL,
  removed TINYINT(1) NOT NULL DEFAULT 0, version BIGINT NOT NULL,
  PRIMARY KEY (id), FOREIGN KEY (facility_id) REFERENCES facility (id),
  FOREIGN KEY (reporter_id) REFERENCES `user` (id), FOREIGN KEY (assignee) REFERENCES `user` (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE inspection_order (
  id BIGINT NOT NULL AUTO_INCREMENT, facility_id BIGINT NOT NULL,
  title VARCHAR(100) NOT NULL, due_at DATETIME(6) NOT NULL, closed TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (id), FOREIGN KEY (facility_id) REFERENCES facility (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- TEST_FIXTURE_DDL: isolated owned schema, not a migration.
CREATE TABLE `user` (
  id BIGINT NOT NULL AUTO_INCREMENT, name VARCHAR(100) NOT NULL,
  PRIMARY KEY (id), UNIQUE KEY roster_user_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE access_token (
  id CHAR(36) NOT NULL, owner_id BIGINT NOT NULL, expires_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id), FOREIGN KEY (owner_id) REFERENCES `user` (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE store (
  id BIGINT NOT NULL AUTO_INCREMENT, name VARCHAR(100) NOT NULL, manager_id BIGINT NOT NULL,
  PRIMARY KEY (id), FOREIGN KEY (manager_id) REFERENCES `user` (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE employee (
  id BIGINT NOT NULL AUTO_INCREMENT, store_id BIGINT NOT NULL, name VARCHAR(100) NOT NULL,
  PRIMARY KEY (id), FOREIGN KEY (store_id) REFERENCES store (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE shift (
  id BIGINT NOT NULL AUTO_INCREMENT, store_id BIGINT NOT NULL, day DATE NOT NULL,
  band VARCHAR(7) NOT NULL,
  PRIMARY KEY (id), UNIQUE KEY roster_shift_slot (store_id,day,band),
  FOREIGN KEY (store_id) REFERENCES store (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE assignment (
  id BIGINT NOT NULL AUTO_INCREMENT, employee_id BIGINT NOT NULL, shift_id BIGINT NOT NULL,
  PRIMARY KEY (id), UNIQUE KEY roster_employee_slot (employee_id,shift_id),
  FOREIGN KEY (employee_id) REFERENCES employee (id), FOREIGN KEY (shift_id) REFERENCES shift (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

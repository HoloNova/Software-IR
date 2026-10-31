-- TEST_FIXTURE_DDL: isolated owned schema; not product initialization/migration.
CREATE TABLE `user` (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    role VARCHAR(6) NOT NULL DEFAULT 'MEMBER',
    PRIMARY KEY (id),
    UNIQUE KEY gym_user_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE access_token (
    id CHAR(36) NOT NULL,
    owner_id BIGINT NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT gym_access_owner FOREIGN KEY (owner_id) REFERENCES `user` (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE program (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY gym_program_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE check_in (
    id BIGINT NOT NULL AUTO_INCREMENT,
    member_id BIGINT NOT NULL,
    program_id BIGINT NOT NULL,
    hours DECIMAL(4,2) NOT NULL,
    checked_in_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT gym_check_in_member FOREIGN KEY (member_id) REFERENCES `user` (id),
    CONSTRAINT gym_check_in_program FOREIGN KEY (program_id) REFERENCES program (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

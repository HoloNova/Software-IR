-- TEST_FIXTURE_DDL: isolated owned schema; not product initialization/migration.
CREATE TABLE `user` (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY events_user_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE access_token (
    id CHAR(36) NOT NULL,
    owner_id BIGINT NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT events_access_owner FOREIGN KEY (owner_id) REFERENCES `user` (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    organizer_id BIGINT NOT NULL,
    title VARCHAR(100) NOT NULL,
    starts_at DATETIME(6) NOT NULL,
    capacity INT NOT NULL,
    registered INT NOT NULL DEFAULT 0,
    fee DECIMAL(10,2) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT events_event_organizer FOREIGN KEY (organizer_id) REFERENCES `user` (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE registration (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id BIGINT NOT NULL,
    attendee_id BIGINT NOT NULL,
    fee DECIMAL(10,2) NOT NULL,
    registered_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY events_registration_once (event_id, attendee_id),
    CONSTRAINT events_registration_event FOREIGN KEY (event_id) REFERENCES event (id),
    CONSTRAINT events_registration_attendee FOREIGN KEY (attendee_id) REFERENCES `user` (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

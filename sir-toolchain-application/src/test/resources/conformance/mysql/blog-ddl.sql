-- TEST_FIXTURE_DDL: isolated owned schema; not product initialization/migration.
CREATE TABLE `user` (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY blog_user_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE access_token (
    id CHAR(36) NOT NULL,
    owner_id BIGINT NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT blog_access_owner FOREIGN KEY (owner_id) REFERENCES `user` (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE category (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY blog_category_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE post (
    id BIGINT NOT NULL AUTO_INCREMENT,
    author_id BIGINT NOT NULL,
    category_id BIGINT NOT NULL,
    title VARCHAR(100) NOT NULL,
    content LONGTEXT NOT NULL,
    state VARCHAR(9) NOT NULL DEFAULT 'DRAFT',
    likes INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT blog_post_author FOREIGN KEY (author_id) REFERENCES `user` (id),
    CONSTRAINT blog_post_category FOREIGN KEY (category_id) REFERENCES category (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE tag (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY blog_tag_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE post_tag (
    id BIGINT NOT NULL AUTO_INCREMENT,
    post_id BIGINT NOT NULL,
    tag_id BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY blog_post_tag (post_id, tag_id),
    CONSTRAINT blog_post_tag_post FOREIGN KEY (post_id) REFERENCES post (id),
    CONSTRAINT blog_post_tag_tag FOREIGN KEY (tag_id) REFERENCES tag (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `like` (
    id BIGINT NOT NULL AUTO_INCREMENT,
    post_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY blog_post_like (post_id, user_id),
    CONSTRAINT blog_like_post FOREIGN KEY (post_id) REFERENCES post (id),
    CONSTRAINT blog_like_user FOREIGN KEY (user_id) REFERENCES `user` (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

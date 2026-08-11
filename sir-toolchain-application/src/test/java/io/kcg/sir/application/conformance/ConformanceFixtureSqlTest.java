package io.kcg.sir.application.conformance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/** Guards the fixture against drifting from the generated Campus Market goods mapping. */
final class ConformanceFixtureSqlTest {

    @Test
    void goodsFixtureMatchesTheGeneratedGoodsPersistenceColumns() throws IOException {
        String ddl = new String(Objects.requireNonNull(
                getClass().getResourceAsStream("/conformance/mysql/campus-market-ddl.sql"),
                "fixture resource").readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(ddl.contains("seller_id BIGINT NOT NULL"));
        assertTrue(ddl.contains("status VARCHAR(32) NOT NULL"));
        assertTrue(ddl.contains("idx_goods_seller ON goods (seller_id)"));
        assertFalse(ddl.contains("seller_actor_id"));
    }
}
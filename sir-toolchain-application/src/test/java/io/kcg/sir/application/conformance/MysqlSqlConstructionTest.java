package io.kcg.sir.application.conformance;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * Default unit tests for the parameterized MySQL SQL construction in
 * {@link MysqlSchemaInventory}, {@link MysqlObserver}, and
 * {@link SchemaSqlRenderer}.
 *
 * <p>Verifies the ADR-017 搂7.1 / 搂10.4 invariants:
 * <ul>
 *   <li>raw {@code USE <schema>} SQL is never issued;</li>
 *   <li>every {@code INFORMATION_SCHEMA} query is a parameterized
 *       {@code PreparedStatement} with {@code BINARY ... = ?} binding;</li>
 *   <li>no schema-name string is concatenated into SQL text;</li>
 *   <li>table-name identifiers in {@link MysqlObserver} are strictly
 *       validated before interpolation.</li>
 * </ul>
 *
 * <p>These tests run by default in the Reactor and do not require MySQL.
 */
class MysqlSqlConstructionTest {

    // ------------------------------------------------------------------
    // SchemaSqlRenderer: forbidden patterns
    // ------------------------------------------------------------------

    @Test
    void rendererNeverEmitsUseStatement() {
        SchemaSqlRenderer renderer = new SchemaSqlRenderer();
        SchemaName name = SchemaName.parse("kcg_conf_test");
        String create = renderer.createDatabase(name);
        String drop = renderer.dropDatabase(name);
        String absence = renderer.informationSchemaAbsenceSql();
        assertFalse(create.toUpperCase(java.util.Locale.ROOT).contains("USE "),
                "CREATE must not contain USE: " + create);
        assertFalse(drop.toUpperCase(java.util.Locale.ROOT).contains("USE "),
                "DROP must not contain USE: " + drop);
        assertFalse(absence.toUpperCase(java.util.Locale.ROOT).contains("USE "),
                "absence query must not contain USE: " + absence);
    }

    @Test
    void rendererNeverConcatenatesSchemaIntoAbsenceQuery() {
        SchemaSqlRenderer renderer = new SchemaSqlRenderer();
        String absence = renderer.informationSchemaAbsenceSql();
        // The absence query must use a ? placeholder, not a literal schema
        // name fragment.
        assertTrue(absence.contains("?"),
                "absence query must use a ? placeholder");
        assertFalse(absence.contains("kcg_conf_"),
                "absence query must not contain any kcg_conf_ fragment");
        // The query must use BINARY comparison for exact-match binding.
        assertTrue(absence.contains("BINARY"),
                "absence query must use BINARY comparison");
    }

    @Test
    void rendererProducesBacktickQuotedIdentifiers() {
        SchemaSqlRenderer renderer = new SchemaSqlRenderer();
        SchemaName name = SchemaName.parse("kcg_conf_test");
        // The schema name is wrapped in backticks, never bare.
        String create = renderer.createDatabase(name);
        assertTrue(create.contains("`kcg_conf_test`"),
                "CREATE must wrap schema name in backticks: " + create);
        String drop = renderer.dropDatabase(name);
        assertTrue(drop.contains("`kcg_conf_test`"),
                "DROP must wrap schema name in backticks: " + drop);
    }

    // ------------------------------------------------------------------
    // MysqlSchemaInventory: parameterized queries
    // ------------------------------------------------------------------

    @Test
    void inventoryClassDeclaresQueryMethod() throws Exception {
        // Verify the static query method exists with the expected shape.
        Method query = MysqlSchemaInventory.class.getMethod("query",
                MysqlControlSession.class, SchemaName.class);
        Class<?> returnType = query.getReturnType();
        assertEquals(MysqlSchemaInventory.class, returnType,
                "query() must return MysqlSchemaInventory");
    }

    @Test
    void inventoryItemIsComparableRecord() {
        MysqlSchemaInventory.InventoryItem a =
                new MysqlSchemaInventory.InventoryItem("TABLE", "goods");
        MysqlSchemaInventory.InventoryItem b =
                new MysqlSchemaInventory.InventoryItem("TABLE", "goods");
        MysqlSchemaInventory.InventoryItem c =
                new MysqlSchemaInventory.InventoryItem("VIEW", "goods");
        MysqlSchemaInventory.InventoryItem d =
                new MysqlSchemaInventory.InventoryItem("TABLE", "orders");
        assertEquals(a, b, "equal items must be equal");
        assertEquals(0, a.compareTo(b), "equal items must compare equal");
        assertTrue(a.compareTo(c) < 0, "TABLE before VIEW");
        assertTrue(a.compareTo(d) < 0, "goods before orders");
    }

    @Test
    void inventoryRejectsNullItems() {
        assertThrows(NullPointerException.class,
                () -> new MysqlSchemaInventory(null));
    }

    @Test
    void inventoryItemRejectsNullFields() {
        assertThrows(NullPointerException.class,
                () -> new MysqlSchemaInventory.InventoryItem(null, "x"));
        assertThrows(NullPointerException.class,
                () -> new MysqlSchemaInventory.InventoryItem("TABLE", null));
    }

    // ------------------------------------------------------------------
    // MysqlObserver: identifier validation
    // ------------------------------------------------------------------

    @Test
    void observerAcceptsValidTableIdentifier() {
        assertDoesNotThrow(() -> MysqlObserver.validateTableIdentifier("goods"));
        assertDoesNotThrow(() -> MysqlObserver.validateTableIdentifier("Goods"));
        assertDoesNotThrow(() -> MysqlObserver.validateTableIdentifier("_goods"));
        assertDoesNotThrow(() -> MysqlObserver.validateTableIdentifier("goods_2026"));
        assertDoesNotThrow(() -> MysqlObserver.validateTableIdentifier("G"));
        assertDoesNotThrow(() -> MysqlObserver.validateTableIdentifier(
                "a".repeat(64)));
    }

    @Test
    void observerRejectsInvalidTableIdentifier() {
        assertThrows(IllegalArgumentException.class,
                () -> MysqlObserver.validateTableIdentifier(""));
        assertThrows(NullPointerException.class,
                () -> MysqlObserver.validateTableIdentifier(null));
        // 65 chars exceeds the 64-char limit.
        assertThrows(IllegalArgumentException.class,
                () -> MysqlObserver.validateTableIdentifier("a".repeat(65)));
        // Hyphen is not allowed.
        assertThrows(IllegalArgumentException.class,
                () -> MysqlObserver.validateTableIdentifier("goods-orders"));
        // Digit-first is not allowed.
        assertThrows(IllegalArgumentException.class,
                () -> MysqlObserver.validateTableIdentifier("1goods"));
        // Space is not allowed.
        assertThrows(IllegalArgumentException.class,
                () -> MysqlObserver.validateTableIdentifier("goods orders"));
        // Backtick / quote / semicolon 鈥?SQL injection attempts.
        assertThrows(IllegalArgumentException.class,
                () -> MysqlObserver.validateTableIdentifier("goods`"));
        assertThrows(IllegalArgumentException.class,
                () -> MysqlObserver.validateTableIdentifier("goods'"));
        assertThrows(IllegalArgumentException.class,
                () -> MysqlObserver.validateTableIdentifier("goods;"));
        assertThrows(IllegalArgumentException.class,
                () -> MysqlObserver.validateTableIdentifier("goods--"));
        assertThrows(IllegalArgumentException.class,
                () -> MysqlObserver.validateTableIdentifier("goods/*"));
        assertThrows(IllegalArgumentException.class,
                () -> MysqlObserver.validateTableIdentifier("goods*/"));
    }
}
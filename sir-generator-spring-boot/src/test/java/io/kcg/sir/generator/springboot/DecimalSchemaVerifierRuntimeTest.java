package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.generator.springboot.api.SpringBootDeploymentGenerator;
import io.kcg.sir.lowering.springboot.api.SpringBootDeploymentPlanner;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Execute the exported verifier, not a reimplementation of its precision comparison or SQL. */
class DecimalSchemaVerifierRuntimeTest {
    private static URLClassLoader loader;
    private static Method verify;
    private static Method matches;
    private static List<String[]> contract;

    @BeforeAll
    static void compileVerifier(@TempDir Path directory) throws Exception {
        String source = GeneratorTestSupport.resourceText("valid/money.sir");
        var plan = new SpringBootDeploymentPlanner().plan(
                GeneratorTestSupport.normalizedText(source), GeneratorTestSupport.lowerText(source));
        var files = new SpringBootDeploymentGenerator().generate(plan);
        String migration = GeneratorTestSupport.contentEndingWith(files, "/Migrate.java");
        String keys = migration.lines().filter(line -> line.contains("private static final Map<List<String[]>, List<String[]>> KEYS"))
                .findFirst().orElseThrow();
        // These methods use only JDK/JDBC. Do not replace Flyway, add a dependency, or rewrite the verifier.
        String verifier = migration.substring(migration.indexOf("private static boolean validColumnShape("), migration.lastIndexOf('}'));
        String matcher = migration.substring(migration.indexOf("private static boolean matches("), migration.indexOf("private static Flyway flyway("));
        Path java = directory.resolve("ExportedSchemaVerifier.java");
        Files.writeString(java, "import java.util.*; import java.sql.*; public final class ExportedSchemaVerifier {\n"
                + keys + "\n" + matcher + verifier + "\n}");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "21", "-d", directory.toString(), java.toString()));
        loader = new URLClassLoader(new java.net.URL[]{directory.toUri().toURL()});
        Class<?> type = loader.loadClass("ExportedSchemaVerifier");
        verify = type.getDeclaredMethod("verify", Connection.class, List.class);
        matches = type.getDeclaredMethod("matches", Connection.class, List.class);
        verify.setAccessible(true);
        matches.setAccessible(true);
        contract = GeneratorTestSupport.contentEndingWith(files, "deploy/schema.tsv").lines()
                .filter(line -> line.startsWith("wallet\t")).map(line -> line.split("\t", -1)).toList();
        assertEquals(3, contract.size());
    }

    @AfterAll
    static void close() throws Exception { if (loader != null) loader.close(); }

    @Test
    void canonicalDefaultAndNullableDecimalMatchTheGeneratedContract() throws Exception {
        assertDoesNotThrow(() -> verify.invoke(null, snapshot(19, 2), contract));
        assertEquals(true, matches.invoke(null, snapshot(19, 2), contract));
    }

    @ParameterizedTest
    @CsvSource({"20,2,balance", "19,3,rebate"})
    void precisionOrScaleAloneRejectsTheSameDefaultTypeAndNullability(int precision, int scale, String field) throws Exception {
        var failure = assertThrows(InvocationTargetException.class,
                () -> verify.invoke(null, snapshot(precision, scale), contract));
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertEquals("decimal precision/scale mismatch: wallet." + field, failure.getCause().getMessage());
        // classify consumes this result and reports the public DRIFT code, not verify's private exception text.
        assertEquals(false, matches.invoke(null, snapshot(precision, scale), contract));
    }

    private static Connection snapshot(int precision, int scale) {
        return proxy(Connection.class, (object, method, arguments) -> switch (method.getName()) {
            case "createStatement" -> proxy(Statement.class, (statement, operation, parameters) -> switch (operation.getName()) {
                case "executeQuery" -> rows(metadata((String) parameters[0], precision, scale));
                case "close" -> null;
                default -> throw new AssertionError("verifier must only query metadata: " + operation);
            });
            case "close" -> null;
            default -> throw new AssertionError(method);
        });
    }

    private static Object[][] metadata(String sql, int precision, int scale) {
        if (sql.contains("information_schema.TABLES"))
            return new Object[][]{{"wallet", "InnoDB", "utf8mb4_0900_bin"}};
        if (sql.contains("information_schema.TRIGGERS")) return new Object[][]{{0L}};
        if (sql.contains("information_schema.COLUMNS")) return new Object[][]{
                {"wallet", "id", "bigint", null, "NO", "auto_increment", "PRI", null, "bigint", null, null, 19, 0},
                {"wallet", "balance", "decimal", null, "NO", "", "", null, "decimal(" + precision + ",2)", null, "0.00", precision, 2},
                {"wallet", "rebate", "decimal", null, "YES", "", "", null, "decimal(19," + scale + ")", null, null, 19, scale}};
        if (sql.contains("information_schema.STATISTICS")) return new Object[][]{{"wallet", "PRIMARY", "id", null}};
        if (sql.contains("information_schema.KEY_COLUMN_USAGE") || sql.contains("information_schema.REFERENTIAL_CONSTRAINTS"))
            return new Object[0][];
        throw new AssertionError("unexpected verifier query: " + sql);
    }

    private static ResultSet rows(Object[][] data) {
        var cursor = new AtomicInteger(-1);
        return proxy(ResultSet.class, (object, method, arguments) -> {
            if (method.getName().equals("next")) return cursor.incrementAndGet() < data.length;
            if (method.getName().equals("close")) return null;
            Object value = data[cursor.get()][(Integer) arguments[0] - 1];
            return switch (method.getName()) {
                case "getString" -> value == null ? null : value.toString();
                case "getInt" -> value == null ? 0 : ((Number) value).intValue();
                case "getLong" -> value == null ? 0L : ((Number) value).longValue();
                case "getObject" -> value;
                default -> throw new AssertionError(method);
            };
        });
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}

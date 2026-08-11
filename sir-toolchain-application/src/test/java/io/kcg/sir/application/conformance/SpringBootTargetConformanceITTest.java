package io.kcg.sir.application.conformance;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class SpringBootTargetConformanceITTest {

    @Test
    void invalidRuntimeEndpointReturnsNotRunBeforeSuiteConstruction() throws Exception {
        Object originalSchemaName = staticField("schemaName").get(null);
        Object originalControlUrl = staticField("controlJdbcUrl").get(null);
        Object originalRuntimeUsername = staticField("runtimeUsername").get(null);
        Object originalRuntimePassword = staticField("runtimePassword").get(null);
        try {
            staticField("schemaName").set(null, SchemaName.parse("kcg_conf_test"));
            staticField("controlJdbcUrl").set(null, "jdbc:mysql://localhost:3306/other_schema");
            staticField("runtimeUsername").set(null, "runtime-user");
            staticField("runtimePassword").set(null, "runtime-password");

            Object result = assertDoesNotThrow(
                    SpringBootTargetConformanceITTest::invokeRunFullConformanceSuite,
                    "invalid endpoint must be represented as a terminal result, not an IT error");
            ConformanceResult.NotRun notRun = assertInstanceOf(
                    ConformanceResult.NotRun.class, result);
            assertEquals(ConformanceFailureKind.PRECONDITION, notRun.failure().kind());
            assertEquals("RUNTIME_JDBC_URL_INVALID", notRun.failure().messageKey());
        } finally {
            staticField("schemaName").set(null, originalSchemaName);
            staticField("controlJdbcUrl").set(null, originalControlUrl);
            staticField("runtimeUsername").set(null, originalRuntimeUsername);
            staticField("runtimePassword").set(null, originalRuntimePassword);
        }
    }

    @Test
    void harnessDriverVersionLookupNeverOpensFallbackJdbcConnection() throws Exception {
        FallbackProbeDriver driver = new FallbackProbeDriver();
        DriverManager.registerDriver(driver);
        try {
            Method method = SpringBootTargetConformanceIT.class.getDeclaredMethod(
                    "resolveHarnessJdbcDriverVersion", String.class);
            method.setAccessible(true);
            String version = (String) method.invoke(null, FallbackProbeDriver.URL);

            assertEquals("unknown", version);
            assertEquals(0, driver.connectCalls.get(),
                    "driver version inspection must not establish any JDBC connection");
            assertTrue(driver.acceptsCalls.get() >= 1,
                    "the driver lookup must still inspect registered driver metadata");
        } finally {
            DriverManager.deregisterDriver(driver);
        }
    }

    private static Object invokeRunFullConformanceSuite() {
        try {
            Method method = SpringBootTargetConformanceIT.class.getDeclaredMethod(
                    "runFullConformanceSuite");
            method.setAccessible(true);
            return method.invoke(new SpringBootTargetConformanceIT());
        } catch (InvocationTargetException e) {
            throw new AssertionError("runFullConformanceSuite threw", e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot invoke runFullConformanceSuite", e);
        }
    }

    private static Field staticField(String name) throws ReflectiveOperationException {
        Field field = SpringBootTargetConformanceIT.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static final class FallbackProbeDriver implements Driver {
        private static final String URL = "jdbc:stage-e-version-probe:never-connect";
        private final AtomicInteger acceptsCalls = new AtomicInteger();
        private final AtomicInteger connectCalls = new AtomicInteger();

        @Override
        public Connection connect(String url, Properties info) throws SQLException {
            connectCalls.incrementAndGet();
            throw new SQLException("probe connection must not be opened");
        }

        @Override
        public boolean acceptsURL(String url) {
            return URL.equals(url) && acceptsCalls.incrementAndGet() > 1;
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
            return new DriverPropertyInfo[0];
        }

        @Override
        public int getMajorVersion() {
            return 1;
        }

        @Override
        public int getMinorVersion() {
            return 0;
        }

        @Override
        public boolean jdbcCompliant() {
            return false;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getGlobal();
        }
    }
}
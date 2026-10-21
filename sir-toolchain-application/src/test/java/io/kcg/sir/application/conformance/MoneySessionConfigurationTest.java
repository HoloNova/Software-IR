package io.kcg.sir.application.conformance;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/** Real Spring Boot binding against the harness's actual environment; no pool, database or application. */
class MoneySessionConfigurationTest {
    @Test
    void nonStrictFixtureBindsInitSqlWithoutRequestingAConnection() {
        var pool = new LazyPoolProperties();
        var environment = BusinessSliceHarness.sessionInitializationEnvironment(true);

        var result = assertDoesNotThrow(() -> bind(environment, pool));

        assertTrue(result.isBound());
        assertEquals("SET SESSION sql_mode=''", pool.getConnectionInitSql());
        assertEquals(0, pool.connectionRequests);
    }

    @Test
    void ordinaryRoundsLeavePoolSessionConfigurationUntouched() {
        var pool = new LazyPoolProperties();
        pool.setConnectionInitSql("SELECT 1");
        var environment = BusinessSliceHarness.sessionInitializationEnvironment(false);

        var result = assertDoesNotThrow(() -> bind(environment, pool));

        assertTrue(environment.isEmpty());
        assertFalse(result.isBound());
        assertEquals("SELECT 1", pool.getConnectionInitSql());
        assertEquals(0, pool.connectionRequests);
    }

    @Test
    void underscoresInsideThePropertyNameTriggerTheLazyConnectionGetter() {
        var pool = new LazyPoolProperties();
        var wrongEnvironment = Map.of("SPRING_DATASOURCE_HIKARI_CONNECTION_INIT_SQL", "SET SESSION sql_mode=''");

        assertThrows(BindException.class, () -> bind(wrongEnvironment, pool));

        assertTrue(pool.connectionRequests > 0, "the malformed environment key traverses connection.init.sql");
    }

    private static BindResult<LazyPoolProperties> bind(Map<String, String> environment, LazyPoolProperties pool) {
        var source = new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new LinkedHashMap<>(environment));
        return new Binder(ConfigurationPropertySources.from(source))
                .bind("spring.datasource.hikari", Bindable.ofInstance(pool));
    }

    /** Its getter catches the same accidental connection traversal without opening a real pool. */
    public static final class LazyPoolProperties {
        private String connectionInitSql;
        private int connectionRequests;

        public String getConnectionInitSql() { return connectionInitSql; }
        public void setConnectionInitSql(String value) { connectionInitSql = value; }

        public Connection getConnection() {
            connectionRequests++;
            throw new IllegalStateException("configuration binding must not request a connection");
        }
    }
}

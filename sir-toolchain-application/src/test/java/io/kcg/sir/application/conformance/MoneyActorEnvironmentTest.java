package io.kcg.sir.application.conformance;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/** Exercises the real Money IT factory and the environment actually shared by Maven and Java. */
class MoneyActorEnvironmentTest {
    @TempDir Path root;

    @Test void bothMoneyStartsEnableTheActualBearerAdapterWithoutFixedIdentity() {
        var environment = BusinessSliceHarnessEvidenceTest.environment(
                root.resolve("work"), root.resolve("evidence"), MoneyBusinessConformanceIT.FIXTURE.enabledProperty());
        var slice = MoneyBusinessConformanceIT.createSlice(environment);
        assertExternal(slice.childEnvironment(slice.runtimeUrl()));

        slice.useNonStrictSqlModeFixture();
        slice.useNonUtcCalendarSessionFixture();
        var restarted = slice.childEnvironment(slice.runtimeUrl());
        assertExternal(restarted);
        assertEquals("Pacific/Honolulu", restarted.get("TZ"));
        assertEquals("SET SESSION sql_mode='', time_zone='+05:30'",
                restarted.get("SPRING_DATASOURCE_HIKARI_CONNECTIONINITSQL"));
    }

    @Test void unauthenticatedSlicesStillDoNotAcquireAnIdentityMode() {
        var slice = BusinessSliceHarnessEvidenceTest.harness(root.resolve("work"), root.resolve("evidence"));
        var child = slice.childEnvironment(slice.runtimeUrl());
        assertFalse(child.containsKey("KCG_ACTOR_IDENTITY_MODE"));
        assertFalse(child.containsKey("KCG_ACTOR_MODE"));
    }

    private static void assertExternal(Map<String, String> child) {
        var spring = new StandardEnvironment();
        spring.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        spring.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.copyOf(child)));
        assertEquals("external", spring.getProperty("kcg.actor-identity.mode"),
                "the generated startup guard reads the actual Spring property, not the legacy variable");
        assertFalse(child.containsKey("KCG_ACTOR_IDENTITY_LOCAL_ID"));
        assertFalse(child.containsKey("KCG_ACTOR_LOCAL_ID"));
        assertFalse(child.containsKey("SPRING_PROFILES_ACTIVE"), "never enable local-fixed to bypass authentication");
    }
}

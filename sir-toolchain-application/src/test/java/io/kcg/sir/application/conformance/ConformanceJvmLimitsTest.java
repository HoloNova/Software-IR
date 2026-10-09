package io.kcg.sir.application.conformance;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConformanceJvmLimitsTest {
    @Test void legacyDefaultAndExplicitLimitsDoNotInheritHostOptions() {
        assertEquals(List.of(), ConformanceJvmLimits.arguments("application", k -> null));
        var values = Map.of("kcg.conformance.maven-xmx-mb", "256", "kcg.conformance.application-xmx-mb", "192", "kcg.conformance.child-metaspace-mb", "128", "MAVEN_OPTS", "unsafe host values");
        assertEquals(List.of("-Xmx256m", "-XX:MaxMetaspaceSize=128m"), ConformanceJvmLimits.arguments("maven", values::get));
        assertEquals(List.of("-Xmx192m", "-XX:MaxMetaspaceSize=128m"), ConformanceJvmLimits.arguments("application", values::get));
    }
    @Test void malformedOrUnboundedOptionsAreRejectedBeforeStartingAChild() {
        for (String value : List.of("0", "63", "2049", "99999", "256 -javaagent:x", "-256", "256m", "")) {
            assertThrows(IllegalArgumentException.class, () -> ConformanceJvmLimits.arguments("maven", k -> k.endsWith("xmx-mb") ? value : null));
        }
        assertThrows(IllegalArgumentException.class, () -> ConformanceJvmLimits.arguments("shell", k -> null));
    }
}

package io.kcg.sir.application.conformance;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Explicit, optional child-JVM limits: the harness must not inherit arbitrary host JVM options. */
final class ConformanceJvmLimits {
    private ConformanceJvmLimits() {}

    static List<String> arguments(String role) { return arguments(role, System::getProperty); }

    static List<String> arguments(String role, Function<String, String> properties) {
        if (!role.equals("maven") && !role.equals("application")) throw new IllegalArgumentException("unknown JVM role");
        var args = new ArrayList<String>();
        add(args, properties, "kcg.conformance." + role + "-xmx-mb", "-Xmx", 64, 2048);
        add(args, properties, "kcg.conformance.child-metaspace-mb", "-XX:MaxMetaspaceSize=", 32, 512);
        return List.copyOf(args);
    }

    private static void add(List<String> args, Function<String, String> properties, String key, String prefix, int min, int max) {
        String text = properties.apply(key);
        if (text == null) return;
        if (!text.matches("[0-9]{1,4}")) throw new IllegalArgumentException("invalid child JVM limit: " + key);
        int value = Integer.parseInt(text);
        if (value < min || value > max) throw new IllegalArgumentException("child JVM limit outside bounds: " + key);
        args.add(prefix + value + "m");
    }
}

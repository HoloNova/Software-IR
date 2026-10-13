package io.kcg.sir.application.api;

import java.time.Duration;
import java.util.Objects;

/**
 * The resource contract of one verification, recorded in its report. Every limit is bounded, because a
 * verification starts real processes: an unlimited build or application is a hung server.
 */
public record ResourceLimits(Duration buildTimeout, Duration startTimeout, Duration businessTimeout,
        int buildHeapMegabytes, int applicationHeapMegabytes, int metaspaceMegabytes, long maxLogBytes) {
    public static final Duration MIN_TIMEOUT = Duration.ofSeconds(1);
    public static final Duration MAX_TIMEOUT = Duration.ofHours(2);

    public ResourceLimits {
        boundTimeout(buildTimeout, "buildTimeout");
        boundTimeout(startTimeout, "startTimeout");
        boundTimeout(businessTimeout, "businessTimeout");
        bound(buildHeapMegabytes, 64, 2048, "buildHeapMegabytes");
        bound(applicationHeapMegabytes, 64, 2048, "applicationHeapMegabytes");
        bound(metaspaceMegabytes, 32, 512, "metaspaceMegabytes");
        bound(maxLogBytes, 64L * 1024, 64L * 1024 * 1024, "maxLogBytes");
    }

    /** Small enough for a development machine; a caller with a bigger machine states its own. */
    public static ResourceLimits defaults() {
        return new ResourceLimits(Duration.ofMinutes(10), Duration.ofMinutes(2), Duration.ofMinutes(5), 256, 256, 128, 8L * 1024 * 1024);
    }

    private static void boundTimeout(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.compareTo(MIN_TIMEOUT) < 0 || value.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException(name + " is outside its bounds");
        }
    }

    private static void bound(long value, long min, long max, String name) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " is outside its bounds");
        }
    }

    /** One stable line for the report. */
    public String canonical() {
        return "buildTimeoutSeconds=" + buildTimeout.toSeconds() + " startTimeoutSeconds=" + startTimeout.toSeconds()
                + " businessTimeoutSeconds=" + businessTimeout.toSeconds() + " buildHeapMb=" + buildHeapMegabytes
                + " applicationHeapMb=" + applicationHeapMegabytes + " metaspaceMb=" + metaspaceMegabytes + " maxLogBytes=" + maxLogBytes;
    }
}

package io.kcg.sir.semantic.feature;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The features a {@code use} declaration can name, and the declarations each one expands into.
 *
 * <p>A feature is a parameterised piece of ordinary SIR: its template is SIR text, and what it expands into is
 * analysed exactly like declarations the author wrote. A feature adds no rule of its own beyond the shape of its
 * parameters, so everything the expansion means is decided by the same analysis as everything else.
 */
public final class Features {
    private Features() {}

    /** What a parameter's value is written as. */
    public enum Kind { NAME, DURATION }

    /**
     * One parameter. A name must match {@code pattern}; a duration must lie within the bounds, in seconds. Without a
     * default the parameter must be written.
     */
    public record Parameter(String name, Kind kind, Optional<String> defaultValue, Pattern pattern, long minSeconds, long maxSeconds,
            String rule) {
        public Parameter {
            Objects.requireNonNull(name);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(defaultValue);
            Objects.requireNonNull(pattern);
            Objects.requireNonNull(rule);
        }

        /** Why {@code value} is not acceptable, or empty. Durations come as an amount and a unit in seconds. */
        public Optional<String> problem(String text, BigInteger seconds) {
            if (kind == Kind.NAME) return pattern.matcher(text).matches() ? Optional.empty() : Optional.of(rule);
            boolean inRange = seconds.compareTo(BigInteger.valueOf(minSeconds)) >= 0 && seconds.compareTo(BigInteger.valueOf(maxSeconds)) <= 0;
            return inRange ? Optional.empty() : Optional.of(rule);
        }
    }

    public record Feature(String name, List<Parameter> parameters, String templateResource) {
        public Feature {
            Objects.requireNonNull(name);
            parameters = List.copyOf(parameters);
            Objects.requireNonNull(templateResource);
        }

        public Optional<Parameter> parameter(String name) {
            return parameters.stream().filter(p -> p.name().equals(name)).findFirst();
        }

        /** The declarations, given the text of every parameter (defaults already applied). */
        public String expand(Map<String, String> values) {
            String text = template(templateResource);
            for (Parameter parameter : parameters) {
                String value = Objects.requireNonNull(values.get(parameter.name()), parameter.name());
                text = text.replace("${" + parameter.name() + "}", value);
            }
            if (text.contains("${")) throw new IllegalStateException("feature template " + name + " has an unfilled placeholder");
            return text;
        }
    }

    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9]{0,63}");

    /**
     * Account-and-password login that issues an access token and a refresh token: login, refresh (which rotates the
     * refresh token and refuses a replayed one) and logout. The account entity must already declare a unique name
     * field and a secret password field.
     */
    public static final Feature DUAL_TOKEN_LOGIN = new Feature("DualTokenLogin", List.of(
        new Parameter("account", Kind.NAME, Optional.empty(), NAME, 0, 0, "account must name the entity that logs in"),
        new Parameter("name", Kind.NAME, Optional.empty(), NAME, 0, 0, "name must name the account's unique name field"),
        new Parameter("password", Kind.NAME, Optional.empty(), NAME, 0, 0, "password must name the account's secret password field"),
        new Parameter("access", Kind.DURATION, Optional.of("15 minutes"), NAME, 60, 86_400, "access must be between 1 minute and 1 day"),
        new Parameter("refresh", Kind.DURATION, Optional.of("7 days"), NAME, 86_400, 31_536_000, "refresh must be between 1 day and 365 days"),
        new Parameter("prefix", Kind.NAME, Optional.of(""), Pattern.compile("([A-Z][A-Za-z0-9]{0,31})?"), 0, 0,
            "prefix must start with an upper-case letter")),
        "/io/kcg/sir/semantic/feature/dual-token-login.sir");

    private static final Map<String, Feature> ALL = Map.of(DUAL_TOKEN_LOGIN.name(), DUAL_TOKEN_LOGIN);

    public static Optional<Feature> named(String name) {
        return Optional.ofNullable(ALL.get(name));
    }

    private static String template(String resource) {
        try (InputStream in = Features.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("missing feature template " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("feature template unreadable: " + resource, e);
        }
    }
}

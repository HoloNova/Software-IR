package io.kcg.sir.semantic.feature;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
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

    /**
     * A feature: its parameters and the text it expands into. The language has some built in; a software can define its
     * own ({@code feature Tagging { ... }}), whose text is the one written in the definition.
     */
    public record Feature(String name, List<Parameter> parameters, Supplier<String> template, boolean builtin) {
        public Feature {
            Objects.requireNonNull(name);
            parameters = List.copyOf(parameters);
            Objects.requireNonNull(template);
        }

        /** A built-in feature whose template is the given resources, one after the other. */
        public Feature(String name, List<Parameter> parameters, String... templateResources) {
            this(name, parameters, () -> java.util.Arrays.stream(templateResources).map(Features::loadTemplate).collect(java.util.stream.Collectors.joining()), true);
        }

        public Optional<Parameter> parameter(String name) {
            return parameters.stream().filter(p -> p.name().equals(name)).findFirst();
        }

        /** The template, read. */
        public FeatureTemplate parsed() {
            return FeatureTemplate.parse(this.template.get());
        }

        /** The declarations, given the text of every parameter (defaults already applied), for a template that reads no entity. */
        public String expand(Map<String, String> values) {
            return expand(values, entity -> Optional.empty());
        }

        /**
         * The declarations, given the text of every parameter (defaults already applied) and the entity a name names.
         *
         * @throws FeatureTemplate.Problem when a value the template reads as an entity names none
         */
        public String expand(Map<String, String> values, java.util.function.Function<String, Optional<FeatureTemplate.Entity>> entities) {
            for (Parameter parameter : parameters) Objects.requireNonNull(values.get(parameter.name()), parameter.name());
            return parsed().render(values, entities);
        }
    }

    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9]{0,63}");

    /**
     * Account-and-password login that issues an access token and a refresh token: login, refresh (which rotates the
     * refresh token and refuses a replayed one) and logout. The feature also gives the account entity the two fields a
     * login needs, a unique name and a secret password, under the names {@code name} and {@code password}: the account
     * declares neither, and the rest of the software uses them like its own fields.
     */
    public static final Feature DUAL_TOKEN_LOGIN = new Feature("DualTokenLogin", List.of(
        new Parameter("account", Kind.NAME, Optional.empty(), NAME, 0, 0, "account must name the entity that logs in"),
        new Parameter("name", Kind.NAME, Optional.of("loginName"), NAME, 0, 0, "name must be a field name for the account's login name"),
        new Parameter("password", Kind.NAME, Optional.of("password"), NAME, 0, 0, "password must be a field name for the account's password"),
        new Parameter("access", Kind.DURATION, Optional.of("15 minutes"), NAME, 60, 86_400, "access must be between 1 minute and 1 day"),
        new Parameter("refresh", Kind.DURATION, Optional.of("7 days"), NAME, 86_400, 31_536_000, "refresh must be between 1 day and 365 days"),
        new Parameter("prefix", Kind.NAME, Optional.of(""), Pattern.compile("([A-Z][A-Za-z0-9]{0,31})?"), 0, 0,
            "prefix must start with an upper-case letter")),
        "/io/kcg/sir/semantic/feature/dual-token-account.sir", "/io/kcg/sir/semantic/feature/dual-token-login.sir");

    /**
     * The same login for an account that already declares its unique name field and its secret password field, named by
     * {@code name} and {@code password}: what the scaffold writes into an existing software. It is not usable as
     * {@code use}; a use always brings the fields.
     */
    public static final Feature DUAL_TOKEN_LOGIN_FOR_EXISTING_ACCOUNT = new Feature("DualTokenLogin", List.of(
        new Parameter("account", Kind.NAME, Optional.empty(), NAME, 0, 0, "account must name the entity that logs in"),
        new Parameter("name", Kind.NAME, Optional.empty(), NAME, 0, 0, "name must name the account's unique name field"),
        new Parameter("password", Kind.NAME, Optional.empty(), NAME, 0, 0, "password must name the account's secret password field"),
        DUAL_TOKEN_LOGIN.parameter("access").orElseThrow(), DUAL_TOKEN_LOGIN.parameter("refresh").orElseThrow(), DUAL_TOKEN_LOGIN.parameter("prefix").orElseThrow()),
        "/io/kcg/sir/semantic/feature/dual-token-login.sir");

    /**
     * Create, show, list a page of, change and delete the rows of an entity: one {@code use Crud { entity: Note; }}
     * gives the five capabilities with their inputs, their view and their errors. Everything comes from the entity:
     *
     * <ul>
     *   <li>creating takes every field but a defaulted one (it gets its default) and the version;</li>
     *   <li>the view shows every field but a secret one;</li>
     *   <li>changing takes the identity and every field but a secret one and the version. A versioned entity is changed
     *       by a patch, only the fields the request carries, and refuses a stale version; any other entity is changed
     *       by giving all those fields;</li>
     *   <li>a row outside the entity's scope is not found, as for every load and find.</li>
     * </ul>
     */
    public static final Feature CRUD = new Feature("Crud", List.of(
        new Parameter("entity", Kind.NAME, Optional.empty(), NAME, 0, 0, "entity must name the entity whose rows are managed")),
        "/io/kcg/sir/semantic/feature/crud.sir");

    private static final Map<String, Feature> ALL = Map.of(DUAL_TOKEN_LOGIN.name(), DUAL_TOKEN_LOGIN, CRUD.name(), CRUD);

    public static Optional<Feature> named(String name) {
        return Optional.ofNullable(ALL.get(name));
    }

    private static String loadTemplate(String resource) {
        try (InputStream in = Features.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("missing feature template " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("feature template unreadable: " + resource, e);
        }
    }
}

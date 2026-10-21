package io.kcg.sir.lowering.springboot.model;

import io.kcg.sir.lowering.api.LoweredNodeId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/** Optional delivery target: physical storage decisions, never a reconstruction from rendered Java. */
public record DeploymentPlan(LoweredNodeId id, ProjectArtifact.MavenProject applicationProject,
        List<Table> tables, boolean credentialAuthentication, Images images, Api api) {
    public DeploymentPlan {
        Objects.requireNonNull(id); Objects.requireNonNull(applicationProject); Objects.requireNonNull(images); Objects.requireNonNull(api);
        tables = List.copyOf(tables);
    }
    private static final int MYSQL_DECIMAL_MAX_PRECISION = 65;
    private static final int MYSQL_DECIMAL_MAX_SCALE = 30;

    public record Images(String builder, String runtime, String mysql) {}

    /** The exposed interface as the target lowered it, so documentation and network tests need no second route list. */
    public record Api(String basePackage, String title, String version, String errorEnvelopeClass, List<Endpoint> endpoints) {
        public Api {
            if (basePackage == null || !basePackage.matches(QUALIFIED) || errorEnvelopeClass == null || !errorEnvelopeClass.matches(QUALIFIED))
                throw new IllegalArgumentException("unsupported Java name: " + basePackage + ", " + errorEnvelopeClass);
            Objects.requireNonNull(title); Objects.requireNonNull(version);
            endpoints = List.copyOf(endpoints);
        }
    }
    /** How an endpoint receives its input: none, a JSON body, or query parameters. */
    public enum Binding { NONE, BODY, QUERY }
    /** Text rules are only listed for a plain (non-patch) JSON body, the only place their violation has a declared envelope. */
    /**
     * One operation of the interface. {@code route} is the path as documented, with its {@code {field}} segments;
     * {@code sample} is a path a check can request, each segment filled with a value of the field's kind.
     */
    public record Endpoint(String method, String route, String sample, boolean authenticated, Binding binding, List<TextRule> textRules,
            List<DeclaredError> errors) {
        public Endpoint {
            if (method == null || !method.matches("GET|POST|PUT|PATCH|DELETE")) throw new IllegalArgumentException("unsupported HTTP method: " + method);
            if (route == null || !route.matches("/[A-Za-z0-9/_{}-]*")) throw new IllegalArgumentException("unsupported route: " + route);
            if (sample == null || !sample.matches("/[A-Za-z0-9/_-]*")) throw new IllegalArgumentException("unsupported sample path: " + sample);
            Objects.requireNonNull(binding);
            textRules = List.copyOf(textRules);
            errors = List.copyOf(errors);
        }
    }
    /** A response the application answers with its error envelope: the request envelope or a declared business failure. */
    public record DeclaredError(int status, String code) {
        public DeclaredError {
            if (status < 400 || status > 499) throw new IllegalArgumentException("unsupported error status: " + status);
            if (code == null || !code.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("unsupported error code: " + code);
        }
    }
    /** A declared notBlank and/or length(min, max) rule of one String property; max is absent when unbounded. */
    public record TextRule(String property, boolean notBlank, int minLength, OptionalInt maxLength) {
        public TextRule {
            if (property == null || !property.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("unsupported property: " + property);
            Objects.requireNonNull(maxLength);
            if (minLength < 0 || maxLength.isPresent() && maxLength.getAsInt() < minLength) throw new IllegalArgumentException("invalid length rule");
        }
    }
    /** {@code symbol} is the semantic identity of the entity the table stores; updates match tables by it, not by name. */
    /**
     * {@code uniqueKeys} are the combinations of columns no two rows may share; each is one index of the table, named
     * after its place among the table's keys.
     */
    public record Table(String symbol, String name, List<Column> columns, List<UniqueKey> uniqueKeys) {
        public Table {
            Objects.requireNonNull(symbol); identifier(name); columns = List.copyOf(columns); uniqueKeys = List.copyOf(uniqueKeys);
            for (UniqueKey key : uniqueKeys)
                for (String column : key.columns())
                    if (columns.stream().noneMatch(c -> c.name().equals(column))) throw new IllegalArgumentException("a unique key names no column of " + name + ": " + column);
        }
        public Table(String symbol, String name, List<Column> columns) { this(symbol, name, columns, List.of()); }
    }
    public record UniqueKey(String name, List<String> columns) {
        public UniqueKey {
            identifier(name); columns = List.copyOf(columns);
            if (columns.size() < 2) throw new IllegalArgumentException("a unique key holds at least two columns");
            columns.forEach(DeploymentPlan::identifier);
        }
    }
    public enum Storage { INT, BIGINT, BOOLEAN, VARCHAR, LONGTEXT, UUID, DATE, DATETIME, DECIMAL }
    public record ForeignKey(String table, String column) {
        public ForeignKey { identifier(table); identifier(column); }
    }
    /**
     * {@code symbol} is the semantic identity of the entity identity or field the column stores. {@code defaultValue} is
     * the value the database gives a row that does not set the column, written as MySQL reports it (text without quotes,
     * a flag as 0 or 1); it is what lets a required column be added to a table that already has rows.
     */
    public record Column(String symbol, String name, Storage storage, int length, boolean nullable, boolean primary,
            boolean autoIncrement, boolean unique, Optional<ForeignKey> reference, Optional<String> defaultValue, Optional<DecimalShape> decimalShape) {
        public Column {
            Objects.requireNonNull(symbol); identifier(name); Objects.requireNonNull(storage); Objects.requireNonNull(reference);
            Objects.requireNonNull(defaultValue); Objects.requireNonNull(decimalShape);
            if ((storage == Storage.DECIMAL) != decimalShape.isPresent()) throw new IllegalArgumentException("decimal storage requires an explicit shape only: " + name);
            if (decimalShape.isPresent() && (decimalShape.get().precision() > MYSQL_DECIMAL_MAX_PRECISION || decimalShape.get().scale() > MYSQL_DECIMAL_MAX_SCALE))
                throw new IllegalArgumentException("decimal shape exceeds MySQL precision 65 / scale 30: " + name);
            if (storage == Storage.VARCHAR && (length < 1 || length > 768)) throw new IllegalArgumentException("invalid VARCHAR length");
            if (defaultValue.isPresent() && (primary || autoIncrement || unique || reference.isPresent()
                    || storage == Storage.LONGTEXT || storage == Storage.DATE || storage == Storage.DATETIME))
                throw new IllegalArgumentException("only a plain number, flag or bounded text column can have a database default: " + name);
            if (decimalShape.isPresent() && defaultValue.isPresent()) {
                var shape = decimalShape.orElseThrow();
                var number = new java.math.BigDecimal(defaultValue.orElseThrow());
                if (!new io.kcg.sir.semantic.type.DecimalPrecision(shape.precision(), shape.scale()).accepts(number))
                    throw new IllegalArgumentException("decimal default cannot be represented exactly: " + name);
                // Bound the physical shape before setScale/toPlainString can expand its representation.
                defaultValue = Optional.of(number.setScale(shape.scale(), java.math.RoundingMode.UNNECESSARY).toPlainString());
            }
        }
        public Column(String symbol, String name, Storage storage, int length, boolean nullable, boolean primary,
                boolean autoIncrement, boolean unique, Optional<ForeignKey> reference, Optional<String> defaultValue) {
            this(symbol, name, storage, length, nullable, primary, autoIncrement, unique, reference, defaultValue, Optional.empty());
        }
        public Column(String symbol, String name, Storage storage, int length, boolean nullable, boolean primary,
                boolean autoIncrement, boolean unique, Optional<ForeignKey> reference) {
            this(symbol, name, storage, length, nullable, primary, autoIncrement, unique, reference, Optional.empty());
        }
    }
    private static final String QUALIFIED = "[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*";
    static void identifier(String name) {
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]{0,63}"))
            throw new IllegalArgumentException("deployment SQL identifier is unsupported: " + name);
    }
}

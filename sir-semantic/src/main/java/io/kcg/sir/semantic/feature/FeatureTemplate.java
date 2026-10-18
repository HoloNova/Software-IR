package io.kcg.sir.semantic.feature;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The text of a feature: SIR with places where the values of the use go.
 *
 * <p>Besides {@code ${parameter}}, a template can go through the fields of the entity a parameter names, and choose
 * text by whether that entity is versioned, so one feature serves every entity:
 *
 * <pre>
 * input Create${entity}Input {
 * ${for f in entity.fields except defaulted, versioned}
 *   field ${f.name}: ${f.type}${f.rules};
 * ${end}
 * }
 * ${if entity.versioned} ... ${else} ... ${end}
 * </pre>
 *
 * <p>Inside a loop {@code ${f.name}} is the field's name, {@code ${f.type}} its type as the entity writes it,
 * {@code ${f.rules}} the rules written on it ({@code " where length(1, 8)"} or nothing) and {@code ${f.base}} the
 * type without the rules a semantic type brings ({@code Text} for a {@code Name}), for the places that may not carry
 * rules. {@code ${entity.identity.name}} and {@code ${entity.identity.type}} are the entity's identity. A line holding
 * only a {@code for}, {@code if}, {@code else} or {@code end} is left out of the result entirely.
 *
 * <p>This is text substitution and nothing more: what the result means is decided by the ordinary analysis of the
 * declarations it reads as, like declarations the author wrote.
 */
public final class FeatureTemplate {
    /** A kind of field a loop can leave out. */
    public enum Exclusion {
        SECRET, VERSIONED, DEFAULTED;

        String word() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** A field of an entity as a template sees it. */
    public record Field(String name, String type, String base, String rules, boolean secret, boolean versioned, boolean defaulted) {
        public Field {
            Objects.requireNonNull(name);
            Objects.requireNonNull(type);
            Objects.requireNonNull(base);
            Objects.requireNonNull(rules);
        }

        boolean excludedBy(Set<Exclusion> exclusions) {
            return secret && exclusions.contains(Exclusion.SECRET) || versioned && exclusions.contains(Exclusion.VERSIONED)
                || defaulted && exclusions.contains(Exclusion.DEFAULTED);
        }
    }

    /** An entity as a template sees it: its identity, then its fields in order, the ones extensions add included. */
    public record Entity(String name, String identityName, String identityType, List<Field> fields) {
        public Entity {
            Objects.requireNonNull(name);
            Objects.requireNonNull(identityName);
            Objects.requireNonNull(identityType);
            fields = List.copyOf(fields);
        }

        boolean versioned() {
            return fields.stream().anyMatch(Field::versioned);
        }
    }

    /** A template that cannot be read, or a use whose values do not fit it. {@code parameter} is the value at fault. */
    public static final class Problem extends RuntimeException {
        private final String parameter;

        Problem(String message) {
            this(message, null);
        }

        Problem(String message, String parameter) {
            super(message);
            this.parameter = parameter;
        }

        public Optional<String> parameter() {
            return Optional.ofNullable(parameter);
        }
    }

    private sealed interface Node permits Text, Value, ForEach, When {}

    private record Text(String text) implements Node {}

    /** {@code ${root}} or {@code ${root.member...}}. */
    private record Value(String root, List<String> members) implements Node {}

    private record ForEach(String variable, String parameter, Set<Exclusion> except, List<Node> body) implements Node {}

    private record When(String parameter, boolean negated, List<Node> then, List<Node> otherwise) implements Node {}

    private static final java.util.regex.Pattern WORD = java.util.regex.Pattern.compile("[A-Za-z][A-Za-z0-9]*");
    private static final Set<String> FIELD_MEMBERS = Set.of("name", "type", "base", "rules");
    private static final Set<String> IDENTITY_MEMBERS = Set.of("name", "type");

    private final List<Node> nodes;

    private FeatureTemplate(List<Node> nodes) {
        this.nodes = nodes;
    }

    /** @throws Problem when the text is not a well-formed template */
    public static FeatureTemplate parse(String text) {
        return new FeatureTemplate(new Reader(text).read());
    }

    /**
     * Why the template does not fit the parameters it is written for, or nothing. {@code entityParameters} are the
     * parameters whose value is a name, the only ones that can name an entity.
     */
    public List<String> problems(Set<String> parameters, Set<String> entityParameters) {
        List<String> problems = new ArrayList<>();
        check(nodes, parameters, entityParameters, Map.of(), problems);
        return problems;
    }

    /** Whether the template reads an entity's fields or identity, so it cannot be expanded before the entities are known. */
    public boolean readsEntities() {
        return reads(nodes);
    }

    /**
     * The text, given the value of every parameter. {@code entities} gives the entity a value names.
     *
     * @throws Problem when a value the template reads as an entity names none
     */
    public String render(Map<String, String> values, Function<String, Optional<Entity>> entities) {
        StringBuilder out = new StringBuilder();
        render(nodes, values, entities, Map.of(), out);
        return out.toString();
    }

    private static void check(List<Node> nodes, Set<String> parameters, Set<String> entityParameters, Map<String, String> loops,
            List<String> problems) {
        for (Node node : nodes) {
            switch (node) {
                case Text text -> { }
                case Value value -> {
                    if (loops.containsKey(value.root())) {
                        if (value.members().size() != 1 || !FIELD_MEMBERS.contains(value.members().get(0))) {
                            problems.add("${" + text(value) + "}: a field has name, type, base and rules");
                        }
                    } else if (!parameters.contains(value.root())) {
                        problems.add("${" + text(value) + "}: " + value.root() + " is not a parameter of this feature");
                    } else if (!value.members().isEmpty()) {
                        if (value.members().size() != 2 || !value.members().get(0).equals("identity") || !IDENTITY_MEMBERS.contains(value.members().get(1))) {
                            problems.add("${" + text(value) + "}: an entity has identity.name and identity.type");
                        } else if (!entityParameters.contains(value.root())) {
                            problems.add("${" + text(value) + "}: " + value.root() + " is not a name, so it cannot name an entity");
                        }
                    }
                }
                case ForEach loop -> {
                    if (!parameters.contains(loop.parameter())) {
                        problems.add("for " + loop.variable() + ": " + loop.parameter() + " is not a parameter of this feature");
                    } else if (!entityParameters.contains(loop.parameter())) {
                        problems.add("for " + loop.variable() + ": " + loop.parameter() + " is not a name, so it cannot name an entity");
                    }
                    if (parameters.contains(loop.variable()) || loops.containsKey(loop.variable())) {
                        problems.add("for " + loop.variable() + ": the name is already taken by a parameter or an enclosing loop");
                    }
                    Map<String, String> inner = new LinkedHashMap<>(loops);
                    inner.put(loop.variable(), loop.parameter());
                    check(loop.body(), parameters, entityParameters, inner, problems);
                }
                case When when -> {
                    if (!parameters.contains(when.parameter())) {
                        problems.add("if " + when.parameter() + ".versioned: " + when.parameter() + " is not a parameter of this feature");
                    } else if (!entityParameters.contains(when.parameter())) {
                        problems.add("if " + when.parameter() + ".versioned: " + when.parameter() + " is not a name, so it cannot name an entity");
                    }
                    check(when.then(), parameters, entityParameters, loops, problems);
                    check(when.otherwise(), parameters, entityParameters, loops, problems);
                }
            }
        }
    }

    private static boolean reads(List<Node> nodes) {
        for (Node node : nodes) {
            boolean reads = switch (node) {
                case Text text -> false;
                case Value value -> !value.members().isEmpty();
                case ForEach loop -> true;
                case When when -> true;
            };
            if (reads) return true;
        }
        return false;
    }

    private static void render(List<Node> nodes, Map<String, String> values, Function<String, Optional<Entity>> entities,
            Map<String, Field> loops, StringBuilder out) {
        for (Node node : nodes) {
            switch (node) {
                case Text text -> out.append(text.text());
                case Value value -> out.append(value(value, values, entities, loops));
                case ForEach loop -> {
                    for (Field field : entity(loop.parameter(), values, entities).fields()) {
                        if (field.excludedBy(loop.except())) continue;
                        Map<String, Field> inner = new LinkedHashMap<>(loops);
                        inner.put(loop.variable(), field);
                        render(loop.body(), values, entities, inner, out);
                    }
                }
                case When when -> {
                    boolean holds = entity(when.parameter(), values, entities).versioned() != when.negated();
                    render(holds ? when.then() : when.otherwise(), values, entities, loops, out);
                }
            }
        }
    }

    private static String value(Value value, Map<String, String> values, Function<String, Optional<Entity>> entities, Map<String, Field> loops) {
        Field field = loops.get(value.root());
        if (field != null) {
            return switch (value.members().get(0)) {
                case "name" -> field.name();
                case "type" -> field.type();
                case "base" -> field.base();
                case "rules" -> field.rules();
                default -> throw new IllegalStateException(text(value));
            };
        }
        if (value.members().isEmpty()) {
            return Objects.requireNonNull(values.get(value.root()), value.root());
        }
        Entity entity = entity(value.root(), values, entities);
        return value.members().get(1).equals("name") ? entity.identityName() : entity.identityType();
    }

    private static Entity entity(String parameter, Map<String, String> values, Function<String, Optional<Entity>> entities) {
        String name = Objects.requireNonNull(values.get(parameter), parameter);
        return entities.apply(name).orElseThrow(() -> new Problem(parameter + " must name an entity, and " + name + " is none", parameter));
    }

    private static String text(Value value) {
        return value.members().isEmpty() ? value.root() : value.root() + "." + String.join(".", value.members());
    }

    /** Reads the text into nodes; a directive alone on its line takes the whole line with it. */
    private static final class Reader {
        private final String text;
        private int position;

        Reader(String text) {
            this.text = text;
        }

        /** An open {@code for} or {@code if}, with the nodes read so far into its current branch. */
        private static final class Open {
            final String head;
            final List<Node> first = new ArrayList<>();
            List<Node> second;

            Open(String head) {
                this.head = head;
            }

            List<Node> current() {
                return second != null ? second : first;
            }
        }

        List<Node> read() {
            List<Node> top = new ArrayList<>();
            Deque<Open> open = new ArrayDeque<>();
            while (position < text.length()) {
                int start = text.indexOf("${", position);
                List<Node> into = open.isEmpty() ? top : open.peek().current();
                if (start < 0) {
                    into.add(new Text(text.substring(position)));
                    break;
                }
                int end = text.indexOf('}', start);
                if (end < 0) throw new Problem("a ${ is not closed by }");
                String inside = text.substring(start + 2, end).trim();
                boolean directive = inside.startsWith("for ") || inside.startsWith("if ") || inside.equals("else") || inside.equals("end");
                int textEnd = start;
                int next = end + 1;
                if (directive) {
                    int lineStart = text.lastIndexOf('\n', start - 1) + 1;
                    int lineEnd = text.indexOf('\n', end);
                    if (text.substring(lineStart, start).isBlank() && text.substring(end + 1, lineEnd < 0 ? text.length() : lineEnd).isBlank()) {
                        textEnd = lineStart;
                        next = lineEnd < 0 ? text.length() : lineEnd + 1;
                    }
                }
                if (textEnd > position) into.add(new Text(text.substring(position, textEnd)));
                position = next;
                if (!directive) {
                    into.add(value(inside));
                } else if (inside.startsWith("for ") || inside.startsWith("if ")) {
                    open.push(new Open(inside));
                } else if (inside.equals("else")) {
                    if (open.isEmpty() || !open.peek().head.startsWith("if ") || open.peek().second != null) {
                        throw new Problem("${else} is not inside an ${if}, or the ${if} has one already");
                    }
                    open.peek().second = new ArrayList<>();
                } else {
                    if (open.isEmpty()) throw new Problem("${end} closes nothing");
                    Open closed = open.pop();
                    (open.isEmpty() ? top : open.peek().current()).add(close(closed));
                }
            }
            if (!open.isEmpty()) throw new Problem("${" + open.peek().head + "} is not closed by ${end}");
            return top;
        }

        private static Node value(String inside) {
            String[] parts = inside.split("\\.", -1);
            for (String part : parts) {
                if (!WORD.matcher(part).matches()) throw new Problem("${" + inside + "} is not a parameter, a field or a directive");
            }
            return new Value(parts[0], List.of(parts).subList(1, parts.length));
        }

        private static Node close(Open open) {
            if (open.head.startsWith("if ")) {
                String condition = open.head.substring(3).trim();
                boolean negated = condition.startsWith("not ");
                if (negated) condition = condition.substring(4).trim();
                if (!condition.endsWith(".versioned") || !WORD.matcher(condition.substring(0, condition.length() - ".versioned".length())).matches()) {
                    throw new Problem("${" + open.head + "}: a condition is written <parameter>.versioned or not <parameter>.versioned");
                }
                return new When(condition.substring(0, condition.length() - ".versioned".length()), negated, List.copyOf(open.first),
                    open.second == null ? List.of() : List.copyOf(open.second));
            }
            // for <variable> in <parameter>.fields [except <exclusion>, ...]
            String rest = open.head.substring(4).trim();
            String[] words = rest.split("\\s+", 4);
            if (words.length < 3 || !words[1].equals("in") || !words[2].endsWith(".fields") || !WORD.matcher(words[0]).matches()
                || !WORD.matcher(words[2].substring(0, words[2].length() - ".fields".length())).matches()) {
                throw new Problem("${" + open.head + "}: a loop is written for <name> in <parameter>.fields, optionally followed by except ...");
            }
            Set<Exclusion> except = EnumSet.noneOf(Exclusion.class);
            if (words.length == 4) {
                if (!words[3].startsWith("except ")) throw new Problem("${" + open.head + "}: only except may follow the fields");
                for (String word : words[3].substring(7).split(",")) {
                    String trimmed = word.trim();
                    Exclusion exclusion = java.util.Arrays.stream(Exclusion.values()).filter(e -> e.word().equals(trimmed)).findFirst()
                        .orElseThrow(() -> new Problem("${" + open.head + "}: a loop can leave out secret, versioned and defaulted fields, not " + trimmed));
                    except.add(exclusion);
                }
            }
            return new ForEach(words[0], words[2].substring(0, words[2].length() - ".fields".length()), except, List.copyOf(open.first));
        }
    }
}

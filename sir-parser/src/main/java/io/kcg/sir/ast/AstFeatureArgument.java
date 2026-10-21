package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.math.BigInteger;
import java.util.Objects;

/** One {@code parameter: value;} of a {@code use} declaration; the value is a name or a duration. */
public record AstFeatureArgument(SourceSpan span, AstName parameter, Value value, boolean defaulted) {
    public AstFeatureArgument(SourceSpan span, AstName parameter, Value value) { this(span, parameter, value, false); }
    public AstFeatureArgument {
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(parameter, "parameter");
        Objects.requireNonNull(value, "value");
    }

    public sealed interface Value {
        SourceSpan span();

        /** The value as the author wrote it, e.g. {@code User} or {@code 15 minutes}. */
        String text();
    }

    /** A name: an entity, a field, or a word the feature defines. */
    public record Word(AstName name, AstNameRef reference) implements Value {
        public Word(AstName name) { this(name, null); }
        public Word {
            Objects.requireNonNull(name, "name");
        }

        @Override
        public SourceSpan span() {
            return name.span();
        }

        @Override
        public String text() {
            return name.text();
        }
    }

    public record Text(SourceSpan span, String text, String value) implements Value {
        public Text { Objects.requireNonNull(span); Objects.requireNonNull(text); Objects.requireNonNull(value); }
    }

    public record Names(SourceSpan span, java.util.List<AstNameRef> items) implements Value {
        public Names { Objects.requireNonNull(span); items = java.util.List.copyOf(items); }
        public String text() { return "[" + items.stream().map(AstNameRef::text).collect(java.util.stream.Collectors.joining(", ")) + "]"; }
    }

    /** A duration such as {@code 15 minutes}; the amount keeps an out-of-range literal reportable. */
    public record Duration(SourceSpan span, BigInteger amount, AstTimeUnit unit) implements Value {
        public Duration {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(unit, "unit");
        }

        @Override
        public String text() {
            String word = switch (unit) {
                case SECONDS -> "second";
                case MINUTES -> "minute";
                case HOURS -> "hour";
                case DAYS -> "day";
            };
            return amount + " " + word + (amount.equals(BigInteger.ONE) ? "" : "s");
        }
    }
}

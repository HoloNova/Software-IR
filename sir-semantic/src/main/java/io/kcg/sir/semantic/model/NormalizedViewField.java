package io.kcg.sir.semantic.model;

import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.semantic.schema.Derived;
import io.kcg.sir.semantic.schema.Fact;
import io.kcg.sir.semantic.schema.Identity;
import io.kcg.sir.semantic.schema.Reference;
import io.kcg.sir.semantic.schema.Source;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.semantic.type.SirType;
import io.kcg.sir.source.SourceSpan;
import java.util.Objects;
import java.util.Optional;

/** A response field owns its name and exactly one bound source of values. */
public record NormalizedViewField(
    @Identity SymbolId id,
    @Fact String name,
    @Source SourceSpan span,
    @Source AstNodeId sourceNodeId,
    @Fact SirType type,
    @Fact Value value,
    @Source Optional<AstNodeId> directNamedTypeReferenceSiteId
) {
    public NormalizedViewField {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(sourceNodeId, "sourceNodeId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(directNamedTypeReferenceSiteId, "directNamedTypeReferenceSiteId");
    }

    /** Compatibility for existing column and relation projections; no synthetic column for expressions. */
    public NormalizedViewField(SymbolId id, String name, SourceSpan span, AstNodeId sourceNodeId, SirType type,
            SymbolId sourceField, Optional<Relation> relation, Optional<AstNodeId> typeSite) {
        this(id, name, span, sourceNodeId, type,
                relation.<Value>map(r -> new Related(sourceField, r)).orElseGet(() -> new Column(sourceField)), typeSite);
    }

    public sealed interface Value permits Column, Related, Computed, CountRelated {}

    public record Column(@Derived SymbolId sourceField) implements Value {
        public Column { Objects.requireNonNull(sourceField, "sourceField"); }
    }

    public record Related(@Derived SymbolId sourceField, @Derived Relation relation) implements Value {
        public Related {
            Objects.requireNonNull(sourceField, "sourceField");
            Objects.requireNonNull(relation, "relation");
        }
    }

    public record Computed(@Derived SymbolId rowSymbol, @Fact NormalizedExpression expression) implements Value {
        public Computed {
            Objects.requireNonNull(rowSymbol, "rowSymbol");
            Objects.requireNonNull(expression, "expression");
        }
    }

    public record CountRelated(@Derived SymbolId rowSymbol, @Reference SymbolId entity,
            @Derived SymbolId connectionField, @Fact NormalizedExpression conditions) implements Value {
        public CountRelated {
            Objects.requireNonNull(rowSymbol, "rowSymbol");
            Objects.requireNonNull(entity, "entity");
            Objects.requireNonNull(connectionField, "connectionField");
            Objects.requireNonNull(conditions, "conditions");
        }
    }

    public SymbolId sourceField() {
        return switch (value) {
            case Column column -> column.sourceField();
            case Related related -> related.sourceField();
            case CountRelated ignored -> throw new IllegalStateException("count projection has no source column");
            case Computed ignored -> throw new IllegalStateException("computed projection has no source column");
        };
    }

    public Optional<Relation> relation() {
        return value instanceof Related related ? Optional.of(related.relation()) : Optional.empty();
    }

    public enum Cardinality { TO_ONE, TO_MANY }

    public record Relation(@Fact Cardinality cardinality, @Reference SymbolId targetView) {
        public Relation {
            Objects.requireNonNull(cardinality, "cardinality");
            Objects.requireNonNull(targetView, "targetView");
        }
    }
}

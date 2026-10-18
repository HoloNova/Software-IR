package io.kcg.sir.semantic.type;

public sealed interface SirType permits PrimitiveType, SemanticType, OptionalType, ListType, PageType, RefType, DeclaredType {
}

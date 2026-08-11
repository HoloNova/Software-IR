package io.kcg.sir.semantic.type;

public sealed interface SirType permits PrimitiveType, OptionalType, ListType, RefType, DeclaredType {
}

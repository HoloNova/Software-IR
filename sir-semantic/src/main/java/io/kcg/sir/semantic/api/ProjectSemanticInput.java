package io.kcg.sir.semantic.api;

import io.kcg.sir.ast.AstSourceUnit;
import java.util.*;

/** Parsed project and its explicitly enumerated fragments, not concatenated source text. */
public record ProjectSemanticInput(AstSourceUnit.Root root, List<AstSourceUnit.Fragment> fragments) {
    public ProjectSemanticInput { Objects.requireNonNull(root); fragments = List.copyOf(fragments); }
}

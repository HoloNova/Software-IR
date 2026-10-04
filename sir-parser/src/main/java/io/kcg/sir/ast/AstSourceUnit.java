package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.*;

/** The distinct 0.2 parser product: one project root or one declaration fragment. */
public sealed interface AstSourceUnit permits AstSourceUnit.Root, AstSourceUnit.Fragment {
    SourceSpan span();
    List<AstImport> imports();
    List<AstDeclaration> declarations();

    record Root(AstDocument document, List<AstSourcePath> sources, List<AstImport> imports) implements AstSourceUnit {
        public Root { Objects.requireNonNull(document); sources = List.copyOf(sources); imports = List.copyOf(imports); }
        @Override public SourceSpan span() { return document.span(); }
        @Override public List<AstDeclaration> declarations() { return document.software().declarations(); }
    }
    record Fragment(AstNodeId id, SourceSpan span, SirVersion version, List<AstImport> imports,
                    List<AstDeclaration> declarations) implements AstSourceUnit {
        public Fragment { Objects.requireNonNull(id); Objects.requireNonNull(span); Objects.requireNonNull(version);
            imports = List.copyOf(imports); declarations = List.copyOf(declarations); }
    }
}

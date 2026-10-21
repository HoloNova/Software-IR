package io.kcg.sir.api;

import io.kcg.sir.internal.DefaultSirParser;

/**
 * Parses SIR source text. A software is one root document, which may list further source files, and the declaration
 * fragments those files hold; a software written in a single file is a root document with no fragments.
 */
public interface SirParser {
    SourceUnitParseResult parseProject(SirSource source);

    SourceUnitParseResult parseFragment(SirSource source);

    /**
     * Parses the declarations a feature expands into. The text has no source of its own: every node gets an id under
     * {@code under} (the {@code use} declaration's id) and the span {@code at} (the {@code use} declaration's span), so
     * the expansion is identified and located by the declaration that asked for it.
     */
    ExpansionParseResult parseExpansion(String declarations, io.kcg.sir.ast.AstNodeId under, io.kcg.sir.source.SourceSpan at);

    default ExpansionParseResult parseExpansion(String declarations, io.kcg.sir.ast.AstNodeId under,
            io.kcg.sir.source.SourceSpan at, java.util.List<ExpansionOrigin> origins) {
        return parseExpansion(declarations, under, at);
    }

    static SirParser create() {
        return new DefaultSirParser();
    }
}

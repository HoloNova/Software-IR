package io.kcg.sir.api;

import io.kcg.sir.internal.DefaultSirParser;

/**
 * Parses SIR source text. A software is one root document, which may list further source files, and the declaration
 * fragments those files hold; a software written in a single file is a root document with no fragments.
 */
public interface SirParser {
    SourceUnitParseResult parseProject(SirSource source);

    SourceUnitParseResult parseFragment(SirSource source);

    static SirParser create() {
        return new DefaultSirParser();
    }
}

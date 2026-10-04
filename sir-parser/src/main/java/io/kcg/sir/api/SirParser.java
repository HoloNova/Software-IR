package io.kcg.sir.api;

import io.kcg.sir.internal.DefaultSirParser;

public interface SirParser {
    ParseResult parse(SirSource source);

    /** Explicit 0.2 project/fragment entries; legacy parse does not accept these inputs. */
    default SourceUnitParseResult parseProject(SirSource source) {
        return new DefaultSirParser().parseProject(source);
    }

    default SourceUnitParseResult parseFragment(SirSource source) {
        return new DefaultSirParser().parseFragment(source);
    }

    static SirParser create() {
        return new DefaultSirParser();
    }
}

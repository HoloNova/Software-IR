package io.kcg.sir.parser;

import io.kcg.sir.ast.AstDocument;
import io.kcg.sir.ast.AstSourceUnit;

/** Reads the document node out of a parsed root, which is what most grammar tests want to look at. */
final class ParseTestSupport {
    private ParseTestSupport() {
    }

    static AstDocument document(AstSourceUnit unit) {
        return ((AstSourceUnit.Root) unit).document();
    }
}

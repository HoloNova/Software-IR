package io.kcg.sir.internal;

import io.kcg.sir.api.SirSource;
import io.kcg.sir.source.SourceSpan;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;

/**
 * Finds the comments of a source text.
 *
 * <p>The parser drops comments (they sit on the lexer's hidden channel and the AST has no place for
 * them). A tool that rewrites part of a file must know which comments a rewrite would touch, so this
 * reads them from the same lexer and reports each one's span in the same coordinates as every AST
 * node: code-point offsets into the text with a leading byte order mark removed.
 *
 * <p>It only reads. Text with lexical errors still yields the comments the lexer recognized, and
 * an unclosed block comment is not reported because it is not a comment.
 */
public final class CommentScanner {
    private static final char BOM = '﻿';

    private CommentScanner() {
    }

    public static List<SourceSpan> scan(SirSource source) {
        Objects.requireNonNull(source, "source");
        String content = source.content();
        String normalized = !content.isEmpty() && content.charAt(0) == BOM ? content.substring(1) : content;
        SourceText text = new SourceText(source.id(), normalized);

        SirLexer lexer = new SirLexer(CharStreams.fromString(normalized, source.id().value()));
        lexer.removeErrorListeners();
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        tokens.fill();

        List<SourceSpan> comments = new ArrayList<>();
        for (Token token : tokens.getTokens()) {
            if (token.getType() == SirLexer.LINE_COMMENT || token.getType() == SirLexer.BLOCK_COMMENT) {
                comments.add(text.span(token));
            }
        }

        return List.copyOf(comments);
    }
}

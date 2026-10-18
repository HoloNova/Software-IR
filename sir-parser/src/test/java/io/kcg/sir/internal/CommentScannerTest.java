package io.kcg.sir.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.api.SirSource;
import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourceSpan;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommentScannerTest {
    private static List<String> commentTexts(String text) {
        List<SourceSpan> spans = CommentScanner.scan(new SirSource(SourceId.of("c"), text));
        int[] cps = (text.startsWith("﻿") ? text.substring(1) : text).codePoints().toArray();
        return spans.stream().map(s -> new String(cps, s.start().codePointOffset(), s.end().codePointOffset() - s.start().codePointOffset())).toList();
    }

    @Test
    void findsLineAndBlockCommentsAtTheirExactSpans() {
        String text = "sir 0.3 // header\nsoftware S { /* inner\n block */ }\n// last";

        assertEquals(List.of("// header", "/* inner\n block */", "// last"), commentTexts(text));
    }

    @Test
    void commentMarkersInsideStringsAreNotComments() {
        assertEquals(List.of("// real"), commentTexts("x \"not // a comment /* either */\" // real"));
    }

    @Test
    void offsetsCountCodePointsAndIgnoreALeadingByteOrderMark() {
        // the emoji is one code point but two UTF-16 units; the BOM is not part of the coordinates
        assertEquals(List.of("// after"), commentTexts("﻿😀 // after"));
        SourceSpan span = CommentScanner.scan(new SirSource(SourceId.of("c"), "﻿😀 // after")).get(0);
        assertEquals(2, span.start().codePointOffset());
    }

    @Test
    void anUnclosedBlockCommentIsNotReportedAsAComment() {
        assertTrue(commentTexts("a /* never closed").isEmpty());
    }

    @Test
    void textWithoutCommentsHasNone() {
        assertTrue(commentTexts("sir 0.3\nsoftware S {}\n").isEmpty());
    }
}

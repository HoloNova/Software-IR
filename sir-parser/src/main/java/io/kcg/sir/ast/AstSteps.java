package io.kcg.sir.ast;

import java.util.List;
import java.util.stream.Stream;

/** Structural traversal only. Lexical resolution must still enter each block's own scope. */
public final class AstSteps {
    private AstSteps() {}
    public static Stream<AstStep> walk(List<AstStep> steps) {
        return steps.stream().flatMap(step -> step instanceof AstEachStep each
                ? Stream.concat(Stream.of(step), walk(each.body())) : Stream.of(step));
    }
}

package io.kcg.sir.ast;

import java.util.List;
import java.util.stream.Stream;

/** Structural traversal only. Lexical resolution must still enter each block's own scope. */
public final class AstSteps {
    private AstSteps() {}
    public static Stream<AstStep> walk(List<AstStep> steps) {
        return steps.stream().flatMap(step -> step instanceof AstEachStep each
                ? Stream.concat(Stream.of(step), walk(each.body()))
                : step instanceof AstRepeatStep repeat ? Stream.concat(Stream.of(step), walk(repeat.body())) : Stream.of(step));
    }

    /** The body of a loop step (each or repeat), or empty for any other step. */
    public static List<AstStep> body(AstStep step) {
        return step instanceof AstEachStep each ? each.body() : step instanceof AstRepeatStep repeat ? repeat.body() : List.of();
    }
}

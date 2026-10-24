package io.kcg.sir.semantic.model;

import java.util.List;
import java.util.stream.Stream;

/** Read-only structural traversal; no lexical scope or execution semantics are inferred here. */
public final class NormalizedSteps {
    private NormalizedSteps() {}
    public static Stream<NormalizedStep> walk(List<NormalizedStep> steps) {
        return steps.stream().flatMap(step -> step instanceof NormalizedStep.EachStep each
                ? Stream.concat(Stream.of(step),walk(each.body())) : Stream.of(step));
    }
}

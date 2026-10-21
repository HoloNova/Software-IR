package io.kcg.sir.api;

import io.kcg.sir.ast.AstNameRef;
import java.util.Objects;

/** A complete substituted name token, in code-point offsets relative to expansion text. */
public record ExpansionOrigin(int start, int end, AstNameRef reference) {
    public ExpansionOrigin {
        if (start < 0 || end <= start) throw new IllegalArgumentException("invalid expansion range");
        Objects.requireNonNull(reference);
    }
}

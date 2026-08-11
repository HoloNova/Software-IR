package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.math.BigInteger;
import java.util.Objects;

public record AstTarget(
        AstNodeId id,
        SourceSpan span,
        AstTargetValue<AstLanguage> language,
        BigInteger languageVersion,
        SourceSpan languageVersionSpan,
        AstTargetValue<AstFramework> framework,
        AstTargetValue<AstPersistence> persistence,
        AstTargetValue<AstDatabase> database,
        AstTargetValue<AstBuildTool> build,
        AstTargetValue<AstInterfaceKind> interfaceKind) implements AstNode {

    public AstTarget {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(languageVersion, "languageVersion");
        Objects.requireNonNull(languageVersionSpan, "languageVersionSpan");
        Objects.requireNonNull(framework, "framework");
        Objects.requireNonNull(persistence, "persistence");
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(build, "build");
        Objects.requireNonNull(interfaceKind, "interfaceKind");
        if (languageVersion.signum() < 0) {
            throw new IllegalArgumentException("languageVersion must not be negative");
        }
    }
}

package io.kcg.sir.application.api;

import io.kcg.sir.application.internal.ProjectSourceReader;
import io.kcg.sir.change.edit.ScaffoldResult.Refused;
import io.kcg.sir.change.edit.ScaffoldResult.Scaffolded;
import io.kcg.sir.change.edit.SourceEditor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Writes the dual-token pattern for an existing software, as declarations the ordinary edit can add.
 *
 * <p>The pattern is plain SIR text with the account entity, its two fields, the tokens' lifetimes and an optional name
 * prefix filled in. It is not a language construct: nothing is expanded at analysis time and nothing is hidden. The
 * declarations are checked by the same formal analysis as any edit (so a name that is taken or an account that lacks
 * the password field is refused with the analysis' own message), returned as command values, and become ordinary
 * source text when the edit saves them. Nothing is written here.
 */
public final class ScaffoldApplication {
    private static final String TEMPLATE = "/io/kcg/sir/application/scaffold/dual-token.sir";

    public ScaffoldResult dualToken(ScaffoldRequest request) {
        Objects.requireNonNull(request);
        var diagnostics = new ArrayList<ExecutionDiagnostic>();
        String problem = request.problem();
        if (problem != null) return new ScaffoldResult.Refused(problem, List.of(), diagnostics);
        var loaded = ProjectSourceReader.read(request.sourceRoot(), request.entry(), diagnostics);
        if (loaded.isEmpty()) return new ScaffoldResult.Failure(diagnostics);
        var result = new SourceEditor().scaffold(loaded.get().snapshot(), render(request));
        if (result instanceof Refused refused) return new ScaffoldResult.Refused(refused.message(), refused.details(), diagnostics);
        var scaffolded = (Scaffolded) result;
        return new ScaffoldResult.Scaffolded(scaffolded.softwareName(), scaffolded.baselineSha256(), scaffolded.declarations(), diagnostics);
    }

    static String render(ScaffoldRequest request) {
        return template()
            .replace("${p}", request.prefix())
            .replace("${account}", request.account())
            .replace("${nameField}", request.nameField())
            .replace("${passwordField}", request.passwordField())
            .replace("${accessMinutes}", Integer.toString(request.accessMinutes()))
            .replace("${refreshDays}", Integer.toString(request.refreshDays()));
    }

    private static String template() {
        try (InputStream in = ScaffoldApplication.class.getResourceAsStream(TEMPLATE)) {
            if (in == null) throw new IllegalStateException("missing scaffold template " + TEMPLATE);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("scaffold template unreadable", e);
        }
    }
}

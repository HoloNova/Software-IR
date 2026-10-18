package io.kcg.sir.application.api;

import io.kcg.sir.application.internal.ProjectSourceReader;
import io.kcg.sir.change.edit.ScaffoldResult.Refused;
import io.kcg.sir.change.edit.ScaffoldResult.Scaffolded;
import io.kcg.sir.change.edit.SourceEditor;
import io.kcg.sir.semantic.feature.Features;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Writes the dual-token pattern for an existing software, as declarations the ordinary edit can add.
 *
 * <p>The pattern is the text the language feature {@code use DualTokenLogin} expands into, less the two fields the
 * feature adds to the account (an existing software's account already has them), with the account entity, those
 * two fields, the tokens' lifetimes and an optional name prefix filled in. The scaffold writes it out as the author's
 * own source (detaching it from the feature, for a login that must differ from the standard one). The
 * declarations are checked by the same formal analysis as any edit (so a name that is taken or an account that lacks
 * the password field is refused with the analysis' own message), returned as command values, and become ordinary
 * source text when the edit saves them. Nothing is written here.
 */
public final class ScaffoldApplication {
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
        return Features.DUAL_TOKEN_LOGIN_FOR_EXISTING_ACCOUNT.expand(Map.of(
            "prefix", request.prefix(),
            "account", request.account(),
            "name", request.nameField(),
            "password", request.passwordField(),
            "access", request.accessMinutes() + " minutes",
            "refresh", request.refreshDays() + " days"));
    }
}

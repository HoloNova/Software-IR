package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.application.api.ScaffoldApplication;
import io.kcg.sir.application.api.ScaffoldRequest;
import io.kcg.sir.application.api.ScaffoldResult;
import io.kcg.sir.application.api.SourceEditRequest;
import io.kcg.sir.application.api.SourceEditResult;
import io.kcg.sir.application.api.SourceEditingApplication;
import io.kcg.sir.application.api.ToolchainApplication;
import io.kcg.sir.application.api.ToolchainRequest;
import io.kcg.sir.application.api.ToolchainResult;
import io.kcg.sir.change.edit.Command;
import io.kcg.sir.change.edit.CommandBatch;
import io.kcg.sir.change.edit.Selector;
import io.kcg.sir.semantic.schema.StructNode;
import io.kcg.sir.semantic.schema.StructValue;
import io.kcg.sir.semantic.schema.SoftwareDraft;
import io.kcg.sir.source.SourceId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The dual-token pattern, fitted to a software. The decisive claims are that what it returns really is a set of
 * declarations the ordinary edit accepts, that the saved candidate generates a project, and that what does not fit is
 * refused with the formal analysis' findings instead of being written.
 */
class ScaffoldApplicationTest {
    @TempDir Path temp;

    private Path source(String name, String replaceFragment, String replacement) throws IOException {
        Path root = Files.createDirectory(temp.resolve(name));
        String text;
        try (var input = ScaffoldApplicationTest.class.getResourceAsStream("/valid/scaffold-base.sir")) {
            text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        if (replaceFragment != null) {
            assertTrue(text.contains(replaceFragment), replaceFragment);
            text = text.replace(replaceFragment, replacement);
        }

        Files.writeString(root.resolve("project.sir"), text);
        return root;
    }

    private static ScaffoldRequest request(Path source, String prefix, int minutes, int days) {
        return new ScaffoldRequest(source, SourceId.of("project.sir"), "User", "name", "passwordHash", prefix, minutes, days);
    }

    private static CommandBatch batchOf(ScaffoldResult.Scaffolded scaffolded) {
        List<Command> commands = new ArrayList<>();
        for (StructNode declaration : scaffolded.declarations()) {
            commands.add(new Command.Add(Selector.root().slot(SoftwareDraft.DECLARATIONS), new StructValue.Child(declaration)));
        }

        return new CommandBatch(CommandBatch.PROTOCOL, scaffolded.softwareName(), scaffolded.baselineSha256(), commands);
    }

    @Test
    void theDeclarationsAreAcceptedByTheEditAndTheSavedCandidateGeneratesAProject() throws Exception {
        Path source = source("sir", null, null);
        var scaffolded = assertInstanceOf(ScaffoldResult.Scaffolded.class, new ScaffoldApplication().dualToken(request(source, "", 20, 3)));

        List<String> names = scaffolded.declarations().stream().map(SoftwareDraft::nameOf).toList();
        assertEquals(13, names.size(), names.toString());
        assertTrue(names.containsAll(List.of("AccessToken", "RefreshToken", "TokenPair", "Login", "Refresh", "Logout", "BadLogin")), names.toString());

        Path candidate = temp.resolve("candidate");
        var saved = assertInstanceOf(SourceEditResult.Saved.class,
            new SourceEditingApplication().edit(new SourceEditRequest(source, SourceId.of("project.sir"), candidate, batchOf(scaffolded))));
        String text = Files.readString(candidate.resolve("project.sir"));
        assertTrue(text.contains("now() + 20 minutes") && text.contains("now() + 3 days"), text);
        assertTrue(saved.changes().size() > 0);

        Path output = temp.resolve("generated");
        assertInstanceOf(ToolchainResult.Success.class,
            new ToolchainApplication().execute(new ToolchainRequest(candidate, SourceId.of("project.sir"), output)));
        assertTrue(Files.exists(output.resolve("src/main/java/example/dualtoken/application/CredentialIdentityAdapter.java")));
    }

    @Test
    void theCommittedScaffoldedFixtureIsExactlyWhatTheScaffoldAndTheEditProduce() throws Exception {
        Path source = source("sir", null, null);
        var scaffolded = assertInstanceOf(ScaffoldResult.Scaffolded.class,
            new ScaffoldApplication().dualToken(request(source, "", ScaffoldRequest.DEFAULT_ACCESS_MINUTES, ScaffoldRequest.DEFAULT_REFRESH_DAYS)));
        Path candidate = temp.resolve("candidate");
        assertInstanceOf(SourceEditResult.Saved.class,
            new SourceEditingApplication().edit(new SourceEditRequest(source, SourceId.of("project.sir"), candidate, batchOf(scaffolded))));

        String committed;
        try (var input = ScaffoldApplicationTest.class.getResourceAsStream("/valid/dual-token-scaffolded.sir")) {
            committed = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertEquals(committed, Files.readString(candidate.resolve("project.sir")),
            "the business conformance fixture must be what the tool writes; regenerate it with `kcg scaffold dual-token ... | kcg edit ...`");
    }

    @Test
    void theSameRequestGivesTheSameDeclarations() throws Exception {
        Path source = source("sir", null, null);
        var first = assertInstanceOf(ScaffoldResult.Scaffolded.class, new ScaffoldApplication().dualToken(request(source, "", 15, 7)));
        var second = assertInstanceOf(ScaffoldResult.Scaffolded.class, new ScaffoldApplication().dualToken(request(source, "", 15, 7)));

        assertEquals(first, second);
    }

    @Test
    void aNameThatIsTakenIsRefusedUntilAPrefixKeepsTheNamesApart() throws Exception {
        Path source = source("sir", "    entity Note persistent {",
            "    entity AccessToken persistent {\n      identity id: Int64 generated auto;\n      field label: String;\n    }\n    entity Note persistent {");

        var refused = assertInstanceOf(ScaffoldResult.Refused.class, new ScaffoldApplication().dualToken(request(source, "", 15, 7)));
        assertTrue(refused.details().stream().anyMatch(detail -> detail.contains("SIR-SYMBOL-001")), refused.details().toString());

        var prefixed = assertInstanceOf(ScaffoldResult.Scaffolded.class, new ScaffoldApplication().dualToken(request(source, "Auth", 15, 7)));
        assertTrue(prefixed.declarations().stream().map(SoftwareDraft::nameOf).toList().contains("AuthAccessToken"));
    }

    @Test
    void anAccountThatLacksWhatTheLoginNeedsIsRefusedWithTheAnalysisFindings() throws Exception {
        Path source = source("sir", "      field passwordHash: String secret;", "      field passwordHash: String;");

        var refused = assertInstanceOf(ScaffoldResult.Refused.class, new ScaffoldApplication().dualToken(request(source, "", 15, 7)));
        assertTrue(refused.details().stream().anyMatch(detail -> detail.contains("SIR-VALID-008")), refused.details().toString());
    }

    @Test
    void parametersOutsideWhatTheLanguageCanCarryAreRefusedBeforeAnyAnalysis() throws Exception {
        Path source = source("sir", null, null);
        for (ScaffoldRequest bad : List.of(request(source, "", 0, 7), request(source, "", 15, 0), request(source, "", 1441, 7),
                request(source, "", 15, 366), request(source, "lower", 15, 7),
                new ScaffoldRequest(source, SourceId.of("project.sir"), "User", "na me", "passwordHash", "", 15, 7))) {
            assertInstanceOf(ScaffoldResult.Refused.class, new ScaffoldApplication().dualToken(bad));
        }
    }

    @Test
    void aSourceThatIsNotAValidSoftwareIsAFailureNotAScaffold() throws Exception {
        Path source = source("sir", "    entity Note persistent {", "    entity Note persistent {\n      field broken");

        var result = new ScaffoldApplication().dualToken(request(source, "", 15, 7));
        assertTrue(result instanceof ScaffoldResult.Failure || result instanceof ScaffoldResult.Refused, result.toString());
    }
}

package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.application.api.*;
import io.kcg.sir.change.edit.*;
import io.kcg.sir.semantic.schema.StructValue;
import io.kcg.sir.source.SourceId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceEditingApplicationTest {
    @TempDir Path temp;
    private static final SourceId ENTRY = SourceId.of("project.sir");
    private static final String SOURCE = """
        sir 0.3
        software Demo {
          metadata { displayName "Demo"; namespace "example.demo"; }
          target { language java 21; framework spring_boot; persistence mybatis_plus;
                   database mysql; build maven; interface rest; }
          sources { }
          imports { }
          declarations { error Invalid http 400; error Obsolete; }
        }
        """;

    private Path source(String text) throws Exception {
        Path root = Files.createDirectory(temp.resolve("sources"));
        Files.writeString(root.resolve("project.sir"), text);
        return root;
    }

    @Test void inspectionAndCandidateEditingLeaveTheBaselineUntouched() throws Exception {
        Path root = source(SOURCE);
        var app = new SourceEditingApplication();
        var info = assertInstanceOf(SourceInspectionResult.Success.class, app.inspect(new SourceInspectionRequest(root, ENTRY)));
        var batch = new CommandBatch(CommandBatch.PROTOCOL, "Demo", info.sources().sha256Hex(), List.of(
            new Command.Update(Selector.root().named("declarations", "Invalid").slot("httpStatus"), new StructValue.Text("409")),
            new Command.Delete(Selector.root().named("declarations", "Obsolete"))));
        Path candidate = temp.resolve("new-parent/candidate");
        var result = app.edit(new SourceEditRequest(root, ENTRY, candidate, batch));
        var saved = assertInstanceOf(SourceEditResult.Saved.class, result, result.toString());
        assertEquals(SOURCE, Files.readString(root.resolve("project.sir")));
        assertEquals(saved.sources().sha256Hex(), assertInstanceOf(SourceInspectionResult.Success.class,
            app.inspect(new SourceInspectionRequest(candidate, ENTRY))).sources().sha256Hex());
        String changed = Files.readString(candidate.resolve("project.sir"));
        assertTrue(changed.contains("error Invalid http 409;"), changed);
        assertFalse(changed.contains("Obsolete"), changed);
        assertEquals(2, saved.changes().size());
        assertFalse(Files.exists(temp.resolve(".git")));
        assertFalse(Files.exists(root.resolve(".git")));
    }

    @Test void staleBatchAndProtectedCommentsNeverPublishACandidate() throws Exception {
        Path root = source(SOURCE.replace("error Invalid http 400;", "error Invalid /* keep */ http 400;"));
        var app = new SourceEditingApplication();
        var info = assertInstanceOf(SourceInspectionResult.Success.class, app.inspect(new SourceInspectionRequest(root, ENTRY)));
        var command = new Command.Update(Selector.root().named("declarations", "Invalid").slot("httpStatus"), new StructValue.Text("409"));
        Path candidate = temp.resolve("candidate");
        var stale = assertInstanceOf(SourceEditResult.Refused.class, app.edit(new SourceEditRequest(root, ENTRY, candidate,
            new CommandBatch(CommandBatch.PROTOCOL, "Demo", "0".repeat(64), List.of(command)))));
        assertEquals(EditResult.Reason.STALE_BASELINE, stale.refusal().reason());
        var protectedResult = assertInstanceOf(SourceEditResult.Refused.class, app.edit(new SourceEditRequest(root, ENTRY, candidate,
            new CommandBatch(CommandBatch.PROTOCOL, "Demo", info.sources().sha256Hex(), List.of(command)))));
        assertEquals(EditResult.Reason.PROTECTED_COMMENT, protectedResult.refusal().reason());
        assertFalse(Files.exists(candidate));
        assertTrue(Files.readString(root.resolve("project.sir")).contains("/* keep */"));
    }

    @Test void existingAndOverlappingCandidateRootsAreRefusedBeforeEditing() throws Exception {
        Path root = source(SOURCE);
        var app = new SourceEditingApplication();
        var info = assertInstanceOf(SourceInspectionResult.Success.class, app.inspect(new SourceInspectionRequest(root, ENTRY)));
        var batch = new CommandBatch(CommandBatch.PROTOCOL, "Demo", info.sources().sha256Hex(),
            List.of(new Command.Delete(Selector.root().named("declarations", "Obsolete"))));
        Path existing = Files.createDirectory(temp.resolve("existing"));
        Files.writeString(existing.resolve("marker"), "keep");
        assertInstanceOf(SourceEditResult.Failure.class, app.edit(new SourceEditRequest(root, ENTRY, existing, batch)));
        assertInstanceOf(SourceEditResult.Failure.class, app.edit(new SourceEditRequest(root, ENTRY, root.resolve("nested"), batch)));
        assertEquals("keep", Files.readString(existing.resolve("marker")));
        assertEquals(SOURCE, Files.readString(root.resolve("project.sir")));
        assertFalse(Files.exists(root.resolve("nested")));
    }

    @Test void malformedSourceAndSymlinkRootsKeepOriginalReadDiagnostics() throws Exception {
        Path root = source("sir 0.3 broken");
        var app = new SourceEditingApplication();
        var bad = assertInstanceOf(SourceInspectionResult.Failure.class, app.inspect(new SourceInspectionRequest(root, ENTRY)));
        assertTrue(bad.diagnostics().stream().anyMatch(ExecutionDiagnostic::isError));
        Path link = Files.createSymbolicLink(temp.resolve("link"), root);
        var denied = assertInstanceOf(SourceInspectionResult.Failure.class, app.inspect(new SourceInspectionRequest(link, ENTRY)));
        assertTrue(denied.diagnostics().stream().anyMatch(d -> d.stage() == ExecutionStage.READ));
    }
}

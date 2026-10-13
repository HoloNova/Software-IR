package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.application.api.CompilationInput;
import io.kcg.sir.application.api.ConflictPolicy;
import io.kcg.sir.application.api.FileDelta;
import io.kcg.sir.application.api.FileDelta.Action;
import io.kcg.sir.application.api.SourceDelta;
import io.kcg.sir.application.api.ToolchainApplication;
import io.kcg.sir.application.api.ToolchainRequest;
import io.kcg.sir.application.api.ToolchainResult;
import io.kcg.sir.application.api.UpdatePreviewApplication;
import io.kcg.sir.application.api.UpdatePreviewRequest;
import io.kcg.sir.application.api.UpdatePreviewResult;
import io.kcg.sir.change.edit.Command;
import io.kcg.sir.change.edit.CommandBatch;
import io.kcg.sir.change.edit.EditResult;
import io.kcg.sir.change.edit.Selector;
import io.kcg.sir.change.edit.SourceEditor;
import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourceSnapshot;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The preview compares two complete revisions. The oracle for the file list is the production
 * toolchain itself: it generates each revision into its own directory, and the preview must say
 * exactly what turns one directory into the other.
 */
class UpdatePreviewTest {
    private static final SourceId SINGLE = SourceId.of("market.sir");
    private static final UpdatePreviewApplication PREVIEW = new UpdatePreviewApplication();

    @TempDir Path temp;

    private static String base() throws Exception {
        return ApplicationTestSupport.resource("valid/campus-market-two-capabilities.sir");
    }

    /** The text of {@code text} with one exact piece replaced; the piece must exist, so a fixture change fails loudly. */
    private static String replace(String text, String from, String to) {
        assertTrue(text.contains(from), "fixture changed, cannot find: " + from);
        return text.replace(from, to);
    }

    /**
     * Candidate that replaces SearchGoods by ListAvailableGoods and tightens PublishGoodsInput.title:
     * every kind of file change in one revision, which the older single-family plan refused.
     */
    private static String mixedCandidate(String base) {
        String renamed = replace(base, "capability SearchGoods {", "capability ListAvailableGoods {");
        String filtered = replace(renamed, "find Goods where item.status == GoodsStatus.AVAILABLE as goods;",
            "find Goods where item.status == GoodsStatus.AVAILABLE and item.title == input.title as goods;");
        return replace(filtered, "input PublishGoodsInput {\n      field title: String where notBlank;",
            "input PublishGoodsInput {\n      field title: String where notBlank, length(1, 80);");
    }

    private Path generate(String source, String directory) throws Exception {
        Path file = this.temp.resolve(directory + ".sir");
        Files.writeString(file, source, StandardCharsets.UTF_8);
        Path out = this.temp.resolve(directory);
        ToolchainResult result = new ToolchainApplication().execute(new ToolchainRequest(file, SINGLE, out, ConflictPolicy.FAIL_IF_EXISTS));
        assertInstanceOf(ToolchainResult.Success.class, result, result.diagnostics().toString());
        return out;
    }

    private static UpdatePreviewResult.Success preview(CompilationInput base, CompilationInput candidate, Path output) {
        UpdatePreviewResult result = PREVIEW.preview(new UpdatePreviewRequest(base, candidate, Optional.ofNullable(output)));
        return assertInstanceOf(UpdatePreviewResult.Success.class, result, result.diagnostics().toString());
    }

    /** What the real toolchain's two directories say about every path. */
    private static Map<String, Action> actionsOf(Map<String, String> before, Map<String, String> after) {
        Map<String, Action> out = new TreeMap<>();
        before.forEach((path, hex) -> out.put(path, !after.containsKey(path) ? Action.DELETE : after.get(path).equals(hex) ? Action.UNCHANGED : Action.UPDATE));
        after.keySet().stream().filter(path -> !before.containsKey(path)).forEach(path -> out.put(path, Action.CREATE));
        return out;
    }

    private static Map<String, Action> actionsOf(FileDelta delta) {
        Map<String, Action> out = new TreeMap<>();
        delta.entries().forEach(e -> out.put(e.path(), e.action()));
        return out;
    }

    @Test
    void oneRevisionCanCreateUpdateAndDeleteFilesAndTheListMatchesWhatTheToolchainWrites() throws Exception {
        String base = base();
        String candidate = mixedCandidate(base);
        Path baseOut = this.generate(base, "base");
        Path candidateOut = this.generate(candidate, "candidate");

        var result = preview(CompilationInput.singleFile(SINGLE, base), CompilationInput.singleFile(SINGLE, candidate), baseOut);

        Map<String, Action> expected = actionsOf(MultiSourceTestSupport.files(baseOut), MultiSourceTestSupport.files(candidateOut));
        assertEquals(expected, actionsOf(result.files()));
        assertTrue(result.files().count(Action.CREATE) > 0, result.files().toString());
        assertTrue(result.files().count(Action.UPDATE) > 0, result.files().toString());
        assertTrue(result.files().count(Action.DELETE) > 0, result.files().toString());
        assertTrue(result.files().count(Action.UNCHANGED) > 0, "files nobody touched are still part of the comparison");
        assertFalse(result.hasConflicts(), "the base directory is exactly what the base generated: " + result.files().conflicts());
        assertFalse(result.sourceOnly());
        assertFalse(result.software().isEmpty());
    }

    @Test
    void sharedProjectLevelFilesAreComparedLikeAnyOtherFile() throws Exception {
        String base = base();
        var result = preview(CompilationInput.singleFile(SINGLE, base), CompilationInput.singleFile(SINGLE, mixedCandidate(base)), null);

        var shared = result.files().entries().stream().filter(FileDelta.Entry::shared).toList();
        assertFalse(shared.isEmpty(), "some generated files belong to no single declaration");
        var owned = result.files().entries().stream().filter(e -> !e.shared()).toList();
        assertFalse(owned.isEmpty());
        assertTrue(result.files().entries().stream().allMatch(e -> e.disk() == FileDelta.Disk.NOT_CHECKED), "no output directory, no disk claims");
    }

    @Test
    void theDeltaOfTheEditorAndTheDeltaOfTheTwoCompiledModelsAgree() throws Exception {
        String base = base();
        CommandBatch batch = new CommandBatch(CommandBatch.PROTOCOL, "CampusMarket", SourceEditor.sha256(base),
            List.of(new Command.Delete(Selector.root().named("declarations", "SearchGoods"))));
        var edited = assertInstanceOf(EditResult.Edited.class, new SourceEditor().edit(base, batch));

        var result = preview(CompilationInput.singleFile(SINGLE, base), CompilationInput.singleFile(SINGLE, edited.source()), null);

        assertEquals(edited.delta(), result.software());
        assertTrue(result.files().count(Action.DELETE) > 0, "removing the only query removes its files");
        assertEquals(0, result.files().count(Action.CREATE));
    }

    @Test
    void aSourceThatOnlyGainsACommentChangesNoSoftwareAndNoFile() throws Exception {
        String base = base();
        String candidate = base.replace("  declarations {", "  // reviewed on 2026-10-13\n  declarations {");
        assertNotEquals(base, candidate);

        var result = preview(CompilationInput.singleFile(SINGLE, base), CompilationInput.singleFile(SINGLE, candidate), null);

        assertTrue(result.sourceOnly());
        assertTrue(result.software().isEmpty());
        assertTrue(result.files().changes().isEmpty());
        assertEquals(SourceDelta.Action.CHANGED, result.source().entries().get(0).action());
    }

    @Test
    void identicalRevisionsAreNotAChangeAtAll() throws Exception {
        String base = base();
        var result = preview(CompilationInput.singleFile(SINGLE, base), CompilationInput.singleFile(SINGLE, base), null);

        assertFalse(result.sourceOnly());
        assertFalse(result.source().changed());
        assertTrue(result.software().isEmpty());
        assertTrue(result.files().changes().isEmpty());
    }

    @Test
    void handEditedMissingAndOccupiedPathsAreConflictsAndNothingIsWritten() throws Exception {
        String base = base();
        String candidate = mixedCandidate(base);
        Path baseOut = this.generate(base, "base");
        Path candidateOut = this.generate(candidate, "candidate");
        Map<String, Action> actions = actionsOf(MultiSourceTestSupport.files(baseOut), MultiSourceTestSupport.files(candidateOut));
        String updated = actions.entrySet().stream().filter(e -> e.getValue() == Action.UPDATE).map(Map.Entry::getKey).findFirst().orElseThrow();
        String deleted = actions.entrySet().stream().filter(e -> e.getValue() == Action.DELETE).map(Map.Entry::getKey).findFirst().orElseThrow();
        String created = actions.entrySet().stream().filter(e -> e.getValue() == Action.CREATE).map(Map.Entry::getKey).findFirst().orElseThrow();
        String untouched = actions.entrySet().stream().filter(e -> e.getValue() == Action.UNCHANGED).map(Map.Entry::getKey).findFirst().orElseThrow();

        Files.writeString(baseOut.resolve(updated), "edited by hand\n");
        Files.delete(baseOut.resolve(deleted));
        Files.createDirectories(baseOut.resolve(created).getParent());
        Files.writeString(baseOut.resolve(created), "somebody else's file\n");
        Files.writeString(baseOut.resolve(untouched), "also edited, but the plan does not touch it\n");
        Map<String, String> before = MultiSourceTestSupport.tree(baseOut);

        var result = preview(CompilationInput.singleFile(SINGLE, base), CompilationInput.singleFile(SINGLE, candidate), baseOut);

        Map<String, FileDelta.Conflict> conflicts = new TreeMap<>();
        result.files().conflicts().forEach(e -> conflicts.put(e.path(), e.conflict().orElseThrow()));
        assertEquals(FileDelta.Conflict.MANAGED_FILE_MODIFIED, conflicts.get(updated));
        assertEquals(FileDelta.Conflict.MANAGED_FILE_MISSING, conflicts.get(deleted));
        assertEquals(FileDelta.Conflict.UNMANAGED_FILE_EXISTS, conflicts.get(created));
        assertFalse(conflicts.containsKey(untouched), "an untouched file is not a conflict even if it drifted");
        assertEquals(FileDelta.Disk.DIFFERS, result.files().entries().stream().filter(e -> e.path().equals(untouched)).findFirst().orElseThrow().disk());
        assertTrue(result.hasConflicts());
        assertEquals(before, MultiSourceTestSupport.tree(baseOut), "the preview only reads");
    }

    @Test
    void aBrokenRevisionIsReportedWithTheSideAndStageThatFailed() throws Exception {
        String base = base();
        String broken = base.replace("input SearchGoodsInput;", "input NoSuchInput;");
        assertNotEquals(base, broken);

        var candidateFailure = assertInstanceOf(UpdatePreviewResult.Failure.class,
            PREVIEW.preview(new UpdatePreviewRequest(CompilationInput.singleFile(SINGLE, base), CompilationInput.singleFile(SINGLE, broken), Optional.empty())));
        assertEquals(UpdatePreviewResult.Side.CANDIDATE, candidateFailure.side());
        assertEquals(io.kcg.sir.application.api.ExecutionStage.SEMANTIC, candidateFailure.failedStage());

        var baseFailure = assertInstanceOf(UpdatePreviewResult.Failure.class,
            PREVIEW.preview(new UpdatePreviewRequest(CompilationInput.singleFile(SINGLE, broken), CompilationInput.singleFile(SINGLE, base), Optional.empty())));
        assertEquals(UpdatePreviewResult.Side.BASE, baseFailure.side());

        var outputFailure = assertInstanceOf(UpdatePreviewResult.Failure.class, PREVIEW.preview(new UpdatePreviewRequest(
            CompilationInput.singleFile(SINGLE, base), CompilationInput.singleFile(SINGLE, base), Optional.of(this.temp.resolve("does-not-exist")))));
        assertEquals(UpdatePreviewResult.Side.OUTPUT_ROOT, outputFailure.side());
    }

    // ---- multi-file projects use the same preview ----

    /** The multi-course fixture with the given {file, from, to} replacements applied. */
    private SourceSnapshot project(String directory, String[]... edits) throws Exception {
        Path source = MultiSourceTestSupport.fixture(this.temp.resolve(directory));
        Map<SourceId, byte[]> inputs = new LinkedHashMap<>();
        for (String file : MultiSourceTestSupport.FILES) {
            String text = Files.readString(source.resolve(file), StandardCharsets.UTF_8);
            for (String[] edit : edits) {
                if (edit[0].equals(file)) {
                    text = replace(text, edit[1], edit[2]);
                }
            }

            inputs.put(SourceId.of(file), text.getBytes(StandardCharsets.UTF_8));
        }

        return new SourceSnapshot(MultiSourceTestSupport.ENTRY, inputs);
    }

    @Test
    void aProjectUpdateChangesOneModuleAndOnlyTheFilesItReaches() throws Exception {
        SourceSnapshot base = this.project("base");
        SourceSnapshot candidate = this.project("candidate", new String[] {"project.sir", "field code: String where notBlank, length(1, 32);", "field code: String where notBlank, length(1, 40);"});

        var result = preview(CompilationInput.project(base), CompilationInput.project(candidate), null);

        var changed = result.source().entries().stream().filter(e -> e.action() == SourceDelta.Action.CHANGED).toList();
        assertEquals(List.of("project.sir"), changed.stream().map(e -> e.source().value()).toList());
        assertFalse(result.software().isEmpty(), "the software did change");
        assertEquals(List.of("src/main/java/com/example/courseadmin/api/CreateCourseInput.java"),
            result.files().changes().stream().map(FileDelta.Entry::path).toList());
    }

    @Test
    void aChangeInASourceFileCanChangeTheSoftwareYetNoJavaFile() throws Exception {
        SourceSnapshot base = this.project("base");
        SourceSnapshot candidate = this.project("candidate", new String[] {"modules/student.sir", "length(1, 100)", "length(1, 120)"});

        var result = preview(CompilationInput.project(base), CompilationInput.project(candidate), null);

        assertFalse(result.software().isEmpty(), "the software did change");
        assertTrue(result.files().changes().isEmpty(), "but this constraint is not rendered into any generated file");
        assertFalse(result.sourceOnly(), "a software change is never source-only, even when no file changes");
    }

    @Test
    void movingADeclarationToAnotherSourceFileChangesTheSourceButNoSoftwareAndNoFile() throws Exception {
        String inProject = "    view StudentSummary from Student {\n      field id: Int64;\n      field studentNo: String;\n      field name: String;\n    }\n";
        String inStudent = "  view StudentSummary from Student {\n    field id: Int64;\n    field studentNo: String;\n    field name: String;\n  }\n";
        SourceSnapshot base = this.project("base");
        SourceSnapshot candidate = this.project("candidate",
            new String[] {"project.sir", inProject, ""},
            new String[] {"project.sir", "import Student from \"modules/student.sir\";", "import Student from \"modules/student.sir\";\n    import StudentSummary from \"modules/student.sir\";"},
            new String[] {"modules/student.sir", "  }\n}", "  }\n" + inStudent + "}"});

        var result = preview(CompilationInput.project(base), CompilationInput.project(candidate), null);

        assertEquals(2, result.source().entries().stream().filter(e -> e.action() == SourceDelta.Action.CHANGED).count());
        assertTrue(result.software().isEmpty(), result.software().toString());
        assertTrue(result.files().changes().isEmpty(), result.files().changes().toString());
        assertTrue(result.sourceOnly());
    }
}

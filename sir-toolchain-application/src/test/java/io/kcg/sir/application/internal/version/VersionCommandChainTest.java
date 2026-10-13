package io.kcg.sir.application.internal.version;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.application.MultiSourceTestSupport;
import io.kcg.sir.application.api.ProjectBaselineApplication;
import io.kcg.sir.application.api.ProjectBaselineInspectionRequest;
import io.kcg.sir.application.api.ProjectBaselineRegistrationRequest;
import io.kcg.sir.application.api.ProjectBaselineResult;
import io.kcg.sir.application.api.ProjectToolchainRequest;
import io.kcg.sir.application.api.ProjectToolchainResult;
import io.kcg.sir.application.api.ToolchainApplication;
import io.kcg.sir.application.api.VersionApplyApplication;
import io.kcg.sir.application.api.VersionApplyRequest;
import io.kcg.sir.application.api.VersionApplyResult;
import io.kcg.sir.application.api.VersionPlan;
import io.kcg.sir.application.api.VersionPlanningApplication;
import io.kcg.sir.application.api.VersionPlanningRequest;
import io.kcg.sir.application.api.VersionPlanningResult;
import io.kcg.sir.application.internal.projectbaseline.ProjectBaselineVerification;
import io.kcg.sir.change.edit.Command;
import io.kcg.sir.change.edit.CommandBatch;
import io.kcg.sir.change.edit.EditResult;
import io.kcg.sir.change.edit.ProjectEditResult;
import io.kcg.sir.change.edit.Selector;
import io.kcg.sir.change.edit.SemanticDelta;
import io.kcg.sir.change.edit.SourceEditor;
import io.kcg.sir.semantic.schema.SlotKind;
import io.kcg.sir.semantic.schema.SoftwareDraft;
import io.kcg.sir.semantic.schema.StructNode;
import io.kcg.sir.semantic.schema.StructValue;
import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourceSnapshot;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The whole path a user takes: add, delete and update commands change the project's sources, the
 * new sources are saved as the next version while the generated project is updated, and the
 * stored version is reopened for the next batch of commands.
 */
class VersionCommandChainTest {
    private static final SourceEditor EDITOR = new SourceEditor();
    private static final Selector DECLARATIONS = Selector.root().slot("declarations");
    private static final String SOFTWARE = "CourseAdmin";

    @TempDir Path temp;

    private Path output;
    private Path state;
    private SourceSnapshot sources;
    private String current;

    private void store(String name) throws Exception {
        Path root = this.temp.resolve(name);
        Files.createDirectories(root);
        Path source = MultiSourceTestSupport.fixture(root);
        this.output = root.resolve("output");
        this.state = root.resolve("state");
        var generated = MultiSourceTestSupport.generate(source, this.output);
        var saved = assertInstanceOf(ProjectBaselineResult.Success.class,
            new ProjectBaselineApplication().register(new ProjectBaselineRegistrationRequest(generated.sources(), this.output, this.state)));
        this.sources = saved.sources();
        this.current = saved.receipt().baselineId();
    }

    private static CommandBatch batch(SourceSnapshot sources, Command... commands) {
        return new CommandBatch(CommandBatch.PROTOCOL, SOFTWARE, SourceEditor.sha256(sources), List.of(commands));
    }

    private static ProjectEditResult.Edited edited(ProjectEditResult result) {
        return assertInstanceOf(ProjectEditResult.Edited.class, result, () -> String.valueOf(result));
    }

    /** Saves the edited sources as the next version through the public entries and checks the generated project. */
    private VersionPlan save(SourceSnapshot candidate, String label) throws Exception {
        var request = new VersionPlanningRequest(this.state, this.output, this.current, candidate);
        var planned = assertInstanceOf(VersionPlanningResult.Planned.class, new VersionPlanningApplication().plan(request), label);
        var applied = assertInstanceOf(VersionApplyResult.Applied.class, new VersionApplyApplication().apply(new VersionApplyRequest(request, planned)), label);
        this.current = applied.candidate().baselineId();
        this.sources = candidate;

        Path fresh = this.temp.resolve("fresh-" + label.replace(' ', '-'));
        for (var file : candidate.manifest().files()) {
            Path path = fresh.resolve("sources").resolve(file.sourceId().value());
            Files.createDirectories(path.getParent());
            Files.write(path, candidate.bytes(file.sourceId()));
        }

        assertInstanceOf(ProjectToolchainResult.Success.class, new ToolchainApplication().executeProject(new ProjectToolchainRequest(fresh.resolve("sources"), candidate.entry(), fresh.resolve("output"))));
        assertEquals(MultiSourceTestSupport.tree(fresh.resolve("output")), MultiSourceTestSupport.tree(this.output), label + ": the generated project equals a fresh generation");
        return planned.plan();
    }

    /** What a user sends back after reopening the stored version. */
    private SourceSnapshot reopen() {
        var inspected = assertInstanceOf(ProjectBaselineResult.Success.class,
            new ProjectBaselineApplication().inspect(new ProjectBaselineInspectionRequest(this.state, this.output, this.current)));
        assertEquals(this.sources.manifest(), inspected.sources().manifest());
        return inspected.sources();
    }

    private static List<SourceId> changedFiles(SourceSnapshot before, SourceSnapshot after) {
        List<SourceId> changed = new ArrayList<>();
        for (var file : after.manifest().files()) {
            if (!java.util.Arrays.equals(before.bytes(file.sourceId()), after.bytes(file.sourceId()))) {
                changed.add(file.sourceId());
            }
        }

        return changed;
    }

    private static StructNode searchByTitleInput() {
        return Nodes.input("SearchByTitleInput", Nodes.field("title", Nodes.primitive("STRING"), Nodes.constraint("notBlank")));
    }

    private static StructNode searchByTitleCapability() {
        return Nodes.queryCapability("SearchCoursesByTitle", "SearchByTitleInput", Nodes.listOfRef("Course"),
            Nodes.find("Course", Nodes.binary(Nodes.member(Nodes.name("item"), "name"), "EQ", Nodes.member(Nodes.name("input"), "title")), "found"),
            Nodes.returnStep(Nodes.name("found")));
    }

    @Test
    void commandsEditAProjectSaveItAsTheNextVersionAndTheStoredVersionCanBeEditedAgain() throws Exception {
        this.store("chain");
        SourceSnapshot v0 = this.sources;

        // 1. add an input and a capability that uses it
        var first = edited(EDITOR.edit(v0, batch(v0,
            new Command.Add(DECLARATIONS, Nodes.child(searchByTitleInput())),
            new Command.Add(DECLARATIONS, Nodes.child(searchByTitleCapability())))));
        assertEquals(List.of(SourceId.of("project.sir")), changedFiles(v0, first.sources()), "only the file the declarations were added to changes");
        assertEquals(2, first.delta().changes().stream().filter(c -> c.kind() == SemanticDelta.Kind.ADD).count(), first.delta().toString());
        VersionPlan plan = this.save(first.sources(), "add");
        assertTrue(plan.count(VersionPlan.Action.CREATE) > 0 && plan.count(VersionPlan.Action.UPDATE) >= 0);

        // 2. reopen the stored version and edit it: relax one workflow condition and delete a capability
        SourceSnapshot stored = this.reopen();
        StructNode relaxed = Nodes.binary(
            Nodes.node("PresentExpression", "target", SlotKind.FACT, Nodes.child(Nodes.member(Nodes.name("input"), "name"))),
            "OR",
            Nodes.node("PresentExpression", "target", SlotKind.FACT, Nodes.child(Nodes.member(Nodes.name("input"), "capacity"))));
        var second = edited(EDITOR.edit(stored, batch(stored,
            new Command.Update(Selector.root().named("declarations", "UpdateCourse").slot("workflow").at("steps", 0).slot("condition"), Nodes.child(relaxed)),
            new Command.Delete(Selector.root().named("declarations", "GetCourse")))));
        assertFalse(second.delta().isEmpty());
        VersionPlan again = this.save(second.sources(), "update and delete");
        assertTrue(again.count(VersionPlan.Action.DELETE) > 0 && again.count(VersionPlan.Action.UPDATE) > 0, again.files().toString());

        // 3. and once more: the stored text is the edited text, byte for byte
        SourceSnapshot latest = this.reopen();
        assertArrayEquals(second.sources().bytes(SourceId.of("project.sir")), latest.bytes(SourceId.of("project.sir")));
        assertTrue(new String(latest.bytes(SourceId.of("project.sir")), StandardCharsets.UTF_8).contains("capability SearchCoursesByTitle"));
        assertFalse(new String(latest.bytes(SourceId.of("project.sir")), StandardCharsets.UTF_8).contains("capability GetCourse"));
    }

    @Test
    void aDeclarationInAnotherFileIsRewrittenInThatFileAndNothingElseChanges() throws Exception {
        this.store("fragment");
        SourceSnapshot v0 = this.sources;
        var model = ProjectBaselineVerification.compile(v0, this.output, new ArrayList<>()).compilation().semanticModel();
        StructNode student = SoftwareDraft.of(model).declaration("NormalizedEntity", "Student").orElseThrow();
        StructNode wider = replaceText(student, "100", "120");
        assertNotEquals(student, wider);

        var result = edited(EDITOR.edit(v0, batch(v0, new Command.Update(Selector.root().named("declarations", "Student"), Nodes.child(wider)))));

        assertEquals(List.of(SourceId.of("modules/student.sir")), changedFiles(v0, result.sources()));
        assertTrue(result.changes().stream().allMatch(c -> c.source().value().equals("modules/student.sir")));
        assertTrue(new String(result.sources().bytes(SourceId.of("modules/student.sir")), StandardCharsets.UTF_8).contains("length(1, 120)"));
        VersionPlan plan = this.save(result.sources(), "fragment");
        assertEquals(List.of(SourceId.of("modules/student.sir")), plan.sources().entries().stream()
            .filter(e -> e.action() == io.kcg.sir.application.api.SourceDelta.Action.CHANGED).map(e -> e.source()).toList());
    }

    @Test
    void aCommentInsideARewrittenDeclarationIsProtectedAndNothingIsProduced() throws Exception {
        this.store("comment");
        SourceSnapshot v0 = this.sources;
        SourceSnapshot withComment = VersionScenarios.edit(v0, new String[] {"modules/student.sir", "field name: String where notBlank, length(1, 100);", "// the display name\n    field name: String where notBlank, length(1, 100);"});
        var model = ProjectBaselineVerification.compile(withComment, this.output, new ArrayList<>()).compilation().semanticModel();
        StructNode wider = replaceText(SoftwareDraft.of(model).declaration("NormalizedEntity", "Student").orElseThrow(), "100", "120");

        var result = EDITOR.edit(withComment, batch(withComment, new Command.Update(Selector.root().named("declarations", "Student"), Nodes.child(wider))));

        var refused = assertInstanceOf(ProjectEditResult.Refused.class, result);
        assertEquals(EditResult.Reason.PROTECTED_COMMENT, refused.reason());
        assertTrue(refused.details().stream().anyMatch(d -> d.contains("modules/student.sir")), refused.details().toString());
    }

    @Test
    void aBatchWrittenForAnotherVersionOrAnotherSoftwareIsRefused() throws Exception {
        this.store("stale");
        SourceSnapshot v0 = this.sources;
        SourceSnapshot other = VersionScenarios.edit(v0, new String[] {"modules/student.sir", "sir 0.2", "sir 0.2\n// another version"});
        Command delete = new Command.Delete(Selector.root().named("declarations", "GetCourse"));

        var stale = EDITOR.edit(other, batch(v0, delete));
        assertEquals(EditResult.Reason.STALE_BASELINE, assertInstanceOf(ProjectEditResult.Refused.class, stale).reason());

        var wrongSoftware = EDITOR.edit(v0, new CommandBatch(CommandBatch.PROTOCOL, "SomethingElse", SourceEditor.sha256(v0), List.of(delete)));
        assertEquals(EditResult.Reason.STALE_BASELINE, assertInstanceOf(ProjectEditResult.Refused.class, wrongSoftware).reason());

        var unknown = EDITOR.edit(v0, new CommandBatch("sir-command/9", SOFTWARE, SourceEditor.sha256(v0), List.of(delete)));
        assertEquals(EditResult.Reason.UNSUPPORTED_BATCH, assertInstanceOf(ProjectEditResult.Refused.class, unknown).reason());
    }

    @Test
    void anEditThatBreaksTheProjectIsRefusedWithTheFormalAnalysisReasonAndTheSourcesAreUntouched() throws Exception {
        this.store("invalid");
        SourceSnapshot v0 = this.sources;
        // removing an entity that other declarations still use
        var result = EDITOR.edit(v0, batch(v0, new Command.Delete(Selector.root().named("declarations", "Course"))));

        var refused = assertInstanceOf(ProjectEditResult.Refused.class, result);
        assertEquals(EditResult.Reason.INVALID_RESULT, refused.reason());
        assertFalse(refused.details().isEmpty());
        assertEquals(v0.manifest(), this.sources.manifest());
    }

    /** The node with every text value equal to {@code from} replaced by {@code to}, wherever it is. */
    private static StructNode replaceText(StructNode node, String from, String to) {
        List<StructNode.Slot> slots = new ArrayList<>();
        for (StructNode.Slot slot : node.slots()) {
            slots.add(new StructNode.Slot(slot.key(), slot.kind(), replaceText(slot.value(), from, to)));
        }

        return new StructNode(node.kind(), slots);
    }

    private static StructValue replaceText(StructValue value, String from, String to) {
        return switch (value) {
            case StructValue.Text text -> new StructValue.Text(text.value().equals(from) ? to : text.value());
            case StructValue.None none -> none;
            case StructValue.Child child -> new StructValue.Child(replaceText(child.node(), from, to));
            case StructValue.Seq seq -> new StructValue.Seq(seq.elements().stream().map(e -> replaceText(e, from, to)).toList());
        };
    }
}

package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.application.api.ValidationDiagnostic;
import io.kcg.sir.application.api.ValidationStopAfter;
import io.kcg.sir.application.internal.CompilationService;
import io.kcg.sir.application.internal.CompilationStages;
import io.kcg.sir.application.internal.ProjectCompilation;
import io.kcg.sir.generator.springboot.api.GeneratedFile;
import io.kcg.sir.generator.springboot.api.SpringBootGenerator;
import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourceSnapshot;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The compilation service gives a multi-file project the same two things a single file already had:
 * the original diagnostics of every stage, and the ability to stop after any stage.
 */
class CompilationServiceTest {
    @TempDir Path temp;

    private SourceSnapshot snapshot() throws Exception {
        return this.snapshotWith(null, null);
    }

    /** The multi-course fixture, optionally with one text replaced in the enrollment module. */
    private SourceSnapshot snapshotWith(String from, String to) throws Exception {
        Path source = ProjectTestSupport.fixture(this.temp);
        Map<SourceId, byte[]> inputs = new LinkedHashMap<>();
        for (String file : ProjectTestSupport.FILES) {
            String text = Files.readString(source.resolve(file), StandardCharsets.UTF_8);
            if (from != null && file.equals("modules/enrollment.sir")) {
                assertTrue(text.contains(from), "fixture changed: " + from);
                text = text.replace(from, to);
            }

            inputs.put(SourceId.of(file), text.getBytes(StandardCharsets.UTF_8));
        }

        return new SourceSnapshot(ProjectTestSupport.ENTRY, inputs);
    }

    private static CompilationStages.Snapshot run(SourceSnapshot snapshot, ValidationStopAfter stop, List<ValidationDiagnostic> raw, List<ExecutionDiagnostic> legacy, List<ValidationStopAfter> trace) {
        return CompilationService.project(snapshot, stop, model -> new SpringBootGenerator().generate(model), legacy, trace::add, raw);
    }

    @Test
    void aProjectCanStopAfterEachStageAndReportsHowFarItGot() throws Exception {
        SourceSnapshot snapshot = this.snapshot();
        for (ValidationStopAfter stop : ValidationStopAfter.values()) {
            List<ValidationStopAfter> trace = new ArrayList<>();
            var result = run(snapshot, stop, new ArrayList<>(), new ArrayList<>(), trace);
            assertTrue(result.success(), stop + ": " + result.diagnostics());
            assertEquals(stop, result.stage());
            assertEquals(stop.ordinal() + 1, trace.size(), "stages entered up to " + stop);
            assertEquals(stop.ordinal() >= ValidationStopAfter.SEMANTIC.ordinal(), result.semanticModel() != null, stop.name());
            assertEquals(stop.ordinal() >= ValidationStopAfter.LOWERING.ordinal(), result.loweredModel() != null, stop.name());
            assertEquals(stop == ValidationStopAfter.GENERATION, !result.files().isEmpty(), stop.name());
        }
    }

    @Test
    void theGeneratedFilesAreTheOnesTheProjectGenerationWritesToDisk() throws Exception {
        Path source = ProjectTestSupport.fixture(this.temp);
        Path output = this.temp.resolve("generated");
        ProjectTestSupport.generate(source, output);
        Map<String, String> onDisk = ProjectTestSupport.files(output);

        Map<SourceId, byte[]> inputs = new LinkedHashMap<>();
        for (String file : ProjectTestSupport.FILES) {
            inputs.put(SourceId.of(file), Files.readAllBytes(source.resolve(file)));
        }

        var result = run(new SourceSnapshot(ProjectTestSupport.ENTRY, inputs), ValidationStopAfter.GENERATION, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        Map<String, String> compiled = new TreeMap<>();
        for (GeneratedFile file : result.files()) {
            compiled.put(file.relativePath(), java.util.HexFormat.of().formatHex(file.content().getBytes(StandardCharsets.UTF_8)));
        }

        assertEquals(onDisk, compiled);
    }

    @Test
    void aSemanticFailureKeepsTheOriginalDiagnosticAndTheLegacyListAgreesWithTheOldEntry() throws Exception {
        SourceSnapshot broken = this.snapshotWith("Ref<Course>", "Ref<Missing>");
        List<ValidationDiagnostic> raw = new ArrayList<>();
        List<ExecutionDiagnostic> legacy = new ArrayList<>();
        var result = run(broken, ValidationStopAfter.GENERATION, raw, legacy, new ArrayList<>());

        assertFalse(result.success());
        assertEquals(ValidationStopAfter.SEMANTIC, result.stage());
        assertTrue(raw.stream().anyMatch(d -> "ERROR".equals(d.severity()) && d.stage() == ValidationStopAfter.SEMANTIC && d.span() != null), raw.toString());

        List<ExecutionDiagnostic> viaOldEntry = new ArrayList<>();
        assertTrue(ProjectCompilation.compile(broken, model -> new SpringBootGenerator().generate(model), viaOldEntry).isEmpty());
        assertEquals(viaOldEntry, legacy, "the old project entry reports exactly the legacy list of the service");
    }

    @Test
    void aParseFailureStopsTheStageAtTheFirstBadFile() throws Exception {
        Map<SourceId, byte[]> inputs = new LinkedHashMap<>();
        for (String file : ProjectTestSupport.FILES) {
            String text = file.equals("modules/course.sir") ? "sir 0.2\nentity {" : ProjectTestSupport.resource("valid/multi-course/" + file);
            inputs.put(SourceId.of(file), text.getBytes(StandardCharsets.UTF_8));
        }

        List<ValidationDiagnostic> raw = new ArrayList<>();
        var result = run(new SourceSnapshot(ProjectTestSupport.ENTRY, inputs), ValidationStopAfter.GENERATION, raw, new ArrayList<>(), new ArrayList<>());
        assertFalse(result.success());
        assertEquals(ValidationStopAfter.PARSE, result.stage());
        assertNull(result.semanticModel());
        assertTrue(raw.stream().allMatch(d -> d.stage() == ValidationStopAfter.PARSE), raw.toString());
        assertTrue(raw.stream().anyMatch(d -> d.span() != null && d.span().source().value().equals("modules/course.sir")), raw.toString());
    }

    @Test
    void anOversizedSourceIsRefusedBeforeAnyParsing() {
        byte[] huge = new byte[1024 * 1024 + 1];
        java.util.Arrays.fill(huge, (byte)' ');
        var snapshot = new SourceSnapshot(ProjectTestSupport.ENTRY, Map.of(ProjectTestSupport.ENTRY, huge));
        List<ValidationDiagnostic> raw = new ArrayList<>();
        var result = run(snapshot, ValidationStopAfter.GENERATION, raw, new ArrayList<>(), new ArrayList<>());
        assertFalse(result.success());
        assertEquals("SIR-APP-SOURCES-LIMIT-001", raw.get(0).code());
    }
}

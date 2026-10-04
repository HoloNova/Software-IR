package io.kcg.sir.application.api;

import static org.junit.jupiter.api.Assertions.*;
import static io.kcg.sir.application.MultiSourceTestSupport.*;
import io.kcg.sir.projectgraph.api.*;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectGenerationFailureTest {
    @TempDir Path temp;
    @Test void injectedGraphFailureNeverWritesAPartialProjectOrChangesSources() throws Exception {
        Path source = fixture(temp); var original = tree(temp);
        var broken = new ToolchainApplication(m -> new io.kcg.sir.generator.springboot.api.SpringBootGenerator().generate(m),
                i -> new ProjectGraphAnalysis.Failure(List.of(ProjectGraphDiagnostic.error("TEST-GRAPH-001", "injected before any write"))));
        var rejected = assertInstanceOf(ProjectToolchainResult.Failure.class, broken.executeProject(new ProjectToolchainRequest(source, ENTRY, temp.resolve("new-output"))));
        assertEquals(ExecutionStage.GRAPH, rejected.failedStage());
        assertEquals(FailureDisposition.NO_CHANGES, rejected.disposition()); assertEquals(original, tree(temp));
    }
}

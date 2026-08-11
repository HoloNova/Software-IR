package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.kcg.sir.application.api.ConflictPolicy;
import io.kcg.sir.application.api.ExecutionStage;
import io.kcg.sir.application.api.FailureDisposition;
import io.kcg.sir.application.api.ToolchainApplication;
import io.kcg.sir.application.api.ToolchainRequest;
import io.kcg.sir.application.api.ToolchainResult;
import io.kcg.sir.source.SourceId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolchainApplicationFailureTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void invalidSirFailsAtParseWithoutCreatingOutputRoot() throws Exception {
        Path source = temporaryDirectory.resolve("invalid.sir");
        Files.writeString(source, "not valid SIR", StandardCharsets.UTF_8);
        Path outputRoot = temporaryDirectory.resolve("generated-project");

        ToolchainResult result = new ToolchainApplication().execute(new ToolchainRequest(
                source.toAbsolutePath(),
                SourceId.of("invalid.sir"),
                outputRoot.toAbsolutePath(),
                ConflictPolicy.FAIL_IF_EXISTS));

        ToolchainResult.Failure failure = assertInstanceOf(ToolchainResult.Failure.class, result);
        assertEquals(ExecutionStage.PARSE, failure.failedStage());
        assertEquals(FailureDisposition.NO_CHANGES, failure.disposition());
        assertFalse(failure.diagnostics().isEmpty());
        assertFalse(Files.exists(outputRoot));
    }
}

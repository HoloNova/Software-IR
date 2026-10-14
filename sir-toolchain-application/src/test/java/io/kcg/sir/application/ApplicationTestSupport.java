package io.kcg.sir.application;

import io.kcg.sir.application.api.ToolchainApplication;
import io.kcg.sir.application.api.ToolchainRequest;
import io.kcg.sir.application.api.ToolchainResult;
import io.kcg.sir.source.SourceId;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shared test support for application-layer tests.
 */
final class ApplicationTestSupport {

    private ApplicationTestSupport() {
    }

    /** The campus-market software as a source root holding one document, project.sir. */
    static Path writeCampusMarketSource(Path dir) throws IOException {
        Path root = Files.createDirectories(dir.resolve("campus-market-sources-" + System.nanoTime()));
        Files.writeString(root.resolve("project.sir"), resource("valid/campus-market.sir"), StandardCharsets.UTF_8);
        return root.toAbsolutePath();
    }

    static ToolchainResult.Success runCampusMarket(Path sourceRoot, Path outputRoot) throws IOException {
        ToolchainResult result = new ToolchainApplication().execute(new ToolchainRequest(
                sourceRoot.toAbsolutePath(), SourceId.of("project.sir"), outputRoot.toAbsolutePath()));
        if (!(result instanceof ToolchainResult.Success success)) {
            ToolchainResult.Failure failure = (ToolchainResult.Failure) result;
            throw new AssertionError("expected Success but got Failure at " + failure.failedStage()
                    + ": " + failure.diagnostics());
        }
        return success;
    }

    static String resource(String path) throws IOException {
        try (InputStream stream = ApplicationTestSupport.class.getResourceAsStream("/" + path)) {
            if (stream == null) {
                throw new IllegalArgumentException("missing resource: " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

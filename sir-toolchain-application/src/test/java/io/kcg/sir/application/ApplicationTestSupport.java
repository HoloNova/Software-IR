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

    static Path writeCampusMarketSource(Path dir) throws IOException {
        Path source = dir.resolve("campus-market.sir");
        Files.writeString(source, resource("valid/campus-market.sir"), StandardCharsets.UTF_8);
        return source.toAbsolutePath();
    }

    static ToolchainResult.Success runCampusMarket(Path source, Path outputRoot) throws IOException {
        return runCampusMarket(source, outputRoot,
                io.kcg.sir.application.api.ConflictPolicy.FAIL_IF_EXISTS);
    }

    static ToolchainResult.Success runCampusMarket(Path source, Path outputRoot,
                                                    io.kcg.sir.application.api.ConflictPolicy policy)
            throws IOException {
        ToolchainResult result = new ToolchainApplication().execute(new ToolchainRequest(
                source.toAbsolutePath(),
                SourceId.of("campus-market.sir"),
                outputRoot.toAbsolutePath(),
                policy));
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

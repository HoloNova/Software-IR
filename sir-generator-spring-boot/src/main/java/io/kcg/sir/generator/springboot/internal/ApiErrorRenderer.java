package io.kcg.sir.generator.springboot.internal;

import io.kcg.sir.generator.springboot.api.GeneratedFile;
import io.kcg.sir.lowering.springboot.model.ProjectArtifact;
import java.util.Optional;

/**
 * The single error shape every failing request answers with.
 *
 * <p>A declared failure, a rejected candidate, and a payload the decoder refused all produce this
 * envelope, so a client reads one contract instead of three.
 */
final class ApiErrorRenderer {
    private ApiErrorRenderer() {
    }

    static GeneratedFile render(ProjectArtifact.ApiErrorResponse artifact) {
        ImportSorter imports = new ImportSorter();
        imports.add("java.util.List");
        String body = renderBody(artifact);
        String content = GenerationContext.assembleSource(artifact.packageName(), imports, body);
        return new GeneratedFile(artifact.path(), content, artifact.id(), Optional.empty());
    }

    private static String renderBody(ProjectArtifact.ApiErrorResponse artifact) {
        String fieldError = artifact.fieldErrorSimpleName();
        StringBuilder out = new StringBuilder();
        out.append("public record ").append(artifact.simpleName())
                .append("(String code, String message, List<").append(fieldError).append("> fields) {\n\n");
        out.append("    public record ").append(fieldError).append("(String path, String code, String message) {\n");
        out.append("    }\n\n");
        out.append("    public static ").append(artifact.simpleName()).append(" of(String code, String message) {\n");
        out.append("        return new ").append(artifact.simpleName()).append("(code, message, List.of());\n");
        out.append("    }\n");
        out.append("}\n");
        return out.toString();
    }
}

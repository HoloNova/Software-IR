package io.kcg.sir.generator.springboot.internal;

import io.kcg.sir.generator.springboot.api.GeneratedFile;
import io.kcg.sir.lowering.springboot.model.ProjectArtifact;
import java.util.Optional;

/**
 * The base type of every declared failure.
 *
 * <p>It carries the three facts a failure answers with — the stable code, the response status, and
 * any field-level rejections — so per-error classes stay one line of declaration and the advice
 * never has to know an error by name.
 */
final class ApiExceptionRenderer {
    private ApiExceptionRenderer() {
    }

    static GeneratedFile render(ProjectArtifact.ApiExceptionBase artifact) {
        ImportSorter imports = new ImportSorter();
        imports.add("java.util.List");
        imports.add("org.springframework.http.HttpStatus");
        String fieldError = artifact.errorResponseSimpleName() + "." + fieldErrorName(artifact);
        String body = renderBody(artifact, fieldError);
        String content = GenerationContext.assembleSource(artifact.packageName(), imports, body);
        return new GeneratedFile(artifact.path(), content, artifact.id(), Optional.empty());
    }

    private static String fieldErrorName(ProjectArtifact.ApiExceptionBase artifact) {
        String type = artifact.fieldErrorTypeName();
        int lastDot = type.lastIndexOf('.');
        return lastDot < 0 ? type : type.substring(lastDot + 1);
    }

    private static String renderBody(ProjectArtifact.ApiExceptionBase artifact, String fieldError) {
        StringBuilder out = new StringBuilder();
        out.append("public class ").append(artifact.simpleName()).append(" extends RuntimeException {\n\n");
        out.append("    private final String code;\n");
        out.append("    private final HttpStatus status;\n");
        out.append("    private final List<").append(fieldError).append("> fields;\n\n");
        out.append("    public ").append(artifact.simpleName()).append("(String code, HttpStatus status) {\n");
        out.append("        this(code, status, List.of());\n");
        out.append("    }\n\n");
        out.append("    public ").append(artifact.simpleName()).append("(String code, HttpStatus status, List<").append(fieldError).append("> fields) {\n");
        out.append("        super(code);\n");
        out.append("        this.code = code;\n");
        out.append("        this.status = status;\n");
        out.append("        this.fields = List.copyOf(fields);\n");
        out.append("    }\n\n");
        out.append("    public String code() {\n");
        out.append("        return this.code;\n");
        out.append("    }\n\n");
        out.append("    public HttpStatus status() {\n");
        out.append("        return this.status;\n");
        out.append("    }\n\n");
        out.append("    public List<").append(fieldError).append("> fields() {\n");
        out.append("        return this.fields;\n");
        out.append("    }\n\n");
        out.append("    /** A rejected request: a field that was missing, malformed, or not allowed. */\n");
        out.append("    public static ").append(artifact.simpleName()).append(" invalidRequest(String path, String code, String message) {\n");
        out.append("        return new ").append(artifact.simpleName()).append("(\"")
                .append(artifact.invalidRequestCode()).append("\", HttpStatus.BAD_REQUEST, List.of(new ").append(fieldError)
                .append("(path, code, message)));\n");
        out.append("    }\n\n");
        out.append("    /** A rejected candidate: every violated constraint, reported together. */\n");
        out.append("    public static ").append(artifact.simpleName()).append(" invalidRequest(List<").append(fieldError).append("> fields) {\n");
        out.append("        return new ").append(artifact.simpleName()).append("(\"").append(artifact.invalidRequestCode())
                .append("\", HttpStatus.BAD_REQUEST, fields);\n");
        out.append("    }\n\n");
        out.append("    /** A rejected request whose envelope is missing a required member. */\n");
        out.append("    public static ").append(artifact.simpleName()).append(" missingMember(String path) {\n");
        out.append("        return invalidRequest(path, \"").append(artifact.invalidRequestCode()).append("\", \"must not be null\");\n");
        out.append("    }\n");
        out.append("}\n");
        return out.toString();
    }
}

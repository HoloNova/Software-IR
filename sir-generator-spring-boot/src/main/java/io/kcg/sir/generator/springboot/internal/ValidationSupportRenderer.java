package io.kcg.sir.generator.springboot.internal;

import io.kcg.sir.generator.springboot.api.GeneratedFile;
import io.kcg.sir.lowering.springboot.model.ProjectArtifact;
import java.util.Optional;

/**
 * The constraint primitives a generated candidate check calls.
 *
 * <p>These are the same obligations the payload annotations express, but evaluated against the value
 * the target is about to write — the merged candidate — rather than against the request alone. A
 * violation is reported with the request property it came from, so a client can act on it.
 */
final class ValidationSupportRenderer {
    private ValidationSupportRenderer() {
    }

    static GeneratedFile render(ProjectArtifact.ValidationSupport artifact) {
        ImportSorter imports = new ImportSorter();
        imports.add("java.math.BigDecimal");
        imports.add("java.util.List");
        String fieldError = artifact.fieldErrorSimpleName();
        String body = renderBody(artifact, fieldError);
        String content = GenerationContext.assembleSource(artifact.packageName(), imports, body);
        return new GeneratedFile(artifact.path(), content, artifact.id(), Optional.empty());
    }

    private static String renderBody(ProjectArtifact.ValidationSupport artifact, String fieldError) {
        StringBuilder out = new StringBuilder();
        out.append("/**\n");
        out.append(" * Candidate checks for the constraints a declared payload field carries.\n");
        out.append(" *\n");
        out.append(" * <p>Each check is silent when the value satisfies it, including when the field is absent:\n");
        out.append(" * an absent change keeps the value the entity already had, which was validated when it was\n");
        out.append(" * last written.\n");
        out.append(" */\n");
        out.append("public final class ").append(artifact.simpleName()).append(" {\n\n");
        out.append("    private ").append(artifact.simpleName()).append("() {\n");
        out.append("    }\n\n");
        renderNotBlank(out, fieldError);
        renderEmail(out, fieldError);
        renderLength(out, fieldError);
        renderBounds(out, fieldError, "min", "must be greater than or equal to", " < 0");
        renderBounds(out, fieldError, "max", "must be less than or equal to", " > 0");
        out.append("    private static void reject(List<").append(fieldError).append("> violations, String path, String code, String message) {\n");
        out.append("        violations.add(new ").append(fieldError).append("(path, code, message));\n");
        out.append("    }\n");
        out.append("}\n");
        return out.toString();
    }

    private static void renderNotBlank(StringBuilder out, String fieldError) {
        out.append("    /** A blank or absent mandatory value. */\n");
        out.append("    public static void notBlank(List<").append(fieldError).append("> violations, String path, String value) {\n");
        out.append("        if (value == null || value.isBlank()) {\n");
        out.append("            reject(violations, path, \"notBlank\", \"must not be blank\");\n");
        out.append("        }\n");
        out.append("    }\n\n");
    }

    private static void renderEmail(StringBuilder out, String fieldError) {
        out.append("    /** The single check an email address must pass; an absent one passes. */\n");
        out.append("    public static void email(List<").append(fieldError).append("> violations, String path, String value) {\n");
        out.append("        if (value != null && !value.matches(\"[^@\\\\s]+@[^@\\\\s]+\")) {\n");
        out.append("            reject(violations, path, \"email\", \"must be a well-formed email address\");\n");
        out.append("        }\n");
        out.append("    }\n\n");
    }

    private static void renderLength(StringBuilder out, String fieldError) {
        out.append("    /** The inclusive size range of a text value, counted in code points; an absent one passes. */\n");
        out.append("    public static void length(List<").append(fieldError).append("> violations, String path, String value, int min, int max) {\n");
        out.append("        if (value == null) {\n");
        out.append("            return;\n");
        out.append("        }\n\n");
        out.append("        int size = value.codePointCount(0, value.length());\n");
        out.append("        if (size < min || size > max) {\n");
        out.append("            reject(violations, path, \"length\", \"size must be between \" + min + \" and \" + max);\n");
        out.append("        }\n");
        out.append("    }\n\n");
    }

    private static void renderBounds(StringBuilder out, String fieldError, String name, String wording, String comparison) {
        out.append("    /** A ").append(name).append(" bound on a numeric value; an absent one passes. */\n");
        out.append("    public static void ").append(name).append("(List<").append(fieldError)
                .append("> violations, String path, BigDecimal value, BigDecimal bound) {\n");
        out.append("        if (value != null && value.compareTo(bound)").append(comparison).append(") {\n");
        out.append("            reject(violations, path, \"").append(name).append("\", \"").append(wording)
                .append(" \" + bound.toPlainString());\n");
        out.append("        }\n");
        out.append("    }\n\n");
    }
}

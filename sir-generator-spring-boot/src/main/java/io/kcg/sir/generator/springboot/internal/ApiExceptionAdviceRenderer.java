package io.kcg.sir.generator.springboot.internal;

import io.kcg.sir.generator.springboot.api.GeneratedFile;
import io.kcg.sir.lowering.springboot.model.ProjectArtifact;
import java.util.Optional;

/**
 * Turns every failure the write slice can produce into the error envelope.
 *
 * <p>Three sources reach it: a declared failure thrown by generated code, a payload the decoder could
 * not bind, and a request that violated a payload contract. Each keeps its own status and, where the
 * decoder reports one, the offending property path.
 */
final class ApiExceptionAdviceRenderer {
    private ApiExceptionAdviceRenderer() {
    }

    static GeneratedFile render(ProjectArtifact.ApiExceptionAdvice artifact) {
        ImportSorter imports = new ImportSorter();
        imports.add("com.fasterxml.jackson.databind.exc.MismatchedInputException");
        imports.add("com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException");
        imports.add("java.util.List");
        imports.add("org.springframework.http.ResponseEntity");
        imports.add("org.springframework.http.converter.HttpMessageNotReadableException");
        imports.add("org.springframework.web.bind.MethodArgumentNotValidException");
        imports.add("org.springframework.web.bind.annotation.ExceptionHandler");
        imports.add("org.springframework.web.bind.annotation.RestControllerAdvice");
        String body = renderBody(artifact);
        String content = GenerationContext.assembleSource(artifact.packageName(), imports, body);
        return new GeneratedFile(artifact.path(), content, artifact.id(), Optional.empty());
    }

    private static String renderBody(ProjectArtifact.ApiExceptionAdvice artifact) {
        String response = artifact.errorResponseSimpleName();
        String fieldError = artifact.fieldErrorSimpleName();
        String exceptionBase = artifact.exceptionBaseSimpleName();
        String invalid = artifact.invalidRequestCode();
        StringBuilder out = new StringBuilder();
        out.append("@RestControllerAdvice\n");
        out.append("public class ").append(artifact.simpleName()).append(" {\n\n");
        out.append("    @ExceptionHandler(").append(exceptionBase).append(".class)\n");
        out.append("    public ResponseEntity<").append(response).append("> handleDeclaredFailure(").append(exceptionBase).append(" failure) {\n");
        out.append("        return ResponseEntity.status(failure.status())\n");
        out.append("                .body(new ").append(response).append("(failure.code(), failure.getMessage(), failure.fields()));\n");
        out.append("    }\n\n");
        out.append("    @ExceptionHandler(MethodArgumentNotValidException.class)\n");
        out.append("    public ResponseEntity<").append(response).append("> handlePayloadViolations(MethodArgumentNotValidException failure) {\n");
        out.append("        List<").append(response).append('.').append(fieldError).append("> fields = failure.getBindingResult()\n");
        out.append("                .getFieldErrors()\n");
        out.append("                .stream()\n");
        out.append("                .map(error -> new ").append(response).append('.').append(fieldError).append("(\n");
        out.append("                        error.getField(),\n");
        out.append("                        error.getCode() == null ? \"").append(invalid).append("\" : error.getCode(),\n");
        out.append("                        error.getDefaultMessage() == null ? \"\" : error.getDefaultMessage()))\n");
        out.append("                .toList();\n");
        out.append("        return ResponseEntity.badRequest().body(new ").append(response).append("(\"").append(invalid)
                .append("\", \"request payload is invalid\", fields));\n");
        out.append("    }\n\n");
        out.append("    @ExceptionHandler(HttpMessageNotReadableException.class)\n");
        out.append("    public ResponseEntity<").append(response).append("> handleUnreadablePayload(HttpMessageNotReadableException failure) {\n");
        out.append("        String path = refusedProperty(failure);\n");
        out.append("        List<").append(response).append('.').append(fieldError).append("> fields = path == null\n");
        out.append("                ? List.of()\n");
        out.append("                : List.of(new ").append(response).append('.').append(fieldError).append("(path, \"").append(invalid)
                .append("\", \"must be a well-formed value\"));\n");
        out.append("        return ResponseEntity.badRequest().body(new ").append(response).append("(\"").append(invalid)
                .append("\", \"request payload is invalid\", fields));\n");
        out.append("    }\n\n");
        out.append("    /**\n");
        out.append("     * The property the decoder refused, when it names one.\n");
        out.append("     *\n");
        out.append("     * <p>An unknown property and a value of the wrong shape are reported by different Jackson\n");
        out.append("     * exceptions, so both are inspected before giving up.\n");
        out.append("     */\n");
        out.append("    private static String refusedProperty(HttpMessageNotReadableException failure) {\n");
        out.append("        Throwable cause = failure.getMostSpecificCause();\n");
        out.append("        if (cause instanceof UnrecognizedPropertyException unrecognized) {\n");
        out.append("            // The refused property is reported at the path it sat at: a change set lives\n");
        out.append("            // under `changes`, so the property name alone would not address it.\n");
        out.append("            String nested = joinedPath(unrecognized.getPath());\n");
        out.append("            return nested == null ? unrecognized.getPropertyName() : nested;\n");
        out.append("        }\n\n");
        out.append("        if (cause instanceof MismatchedInputException mismatched) {\n");
        out.append("            return joinedPath(mismatched.getPath());\n");
        out.append("        }\n\n");
        out.append("        return null;\n");
        out.append("    }\n\n");
        out.append("    /** The dotted path a decoder reported, or null when it named no property. */\n");
        out.append("    private static String joinedPath(List<com.fasterxml.jackson.databind.JsonMappingException.Reference> path) {\n");
        out.append("        if (path == null || path.isEmpty()) {\n");
        out.append("            return null;\n");
        out.append("        }\n\n");
        out.append("        StringBuilder joined = new StringBuilder();\n");
        out.append("        for (com.fasterxml.jackson.databind.JsonMappingException.Reference reference : path) {\n");
        out.append("            if (reference.getFieldName() == null) {\n");
        out.append("                continue;\n");
        out.append("            }\n\n");
        out.append("            if (joined.length() > 0) {\n");
        out.append("                joined.append('.');\n");
        out.append("            }\n\n");
        out.append("            joined.append(reference.getFieldName());\n");
        out.append("        }\n\n");
        out.append("        return joined.length() == 0 ? null : joined.toString();\n");
        out.append("    }\n");
        out.append("}\n");
        return out.toString();
    }
}

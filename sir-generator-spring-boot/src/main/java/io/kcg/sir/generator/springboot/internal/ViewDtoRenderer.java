package io.kcg.sir.generator.springboot.internal;

import io.kcg.sir.generator.springboot.api.GeneratedFile;
import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.lowering.springboot.model.SpringArtifact;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.ViewDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.ViewField;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.Optional;

/**
 * Renders a response projection.
 *
 * <p>Unlike a request DTO a view carries no validation annotations: it is written by the service and
 * only ever read by the serializer, so it exposes getters and one all-arguments constructor.
 */
final class ViewDtoRenderer {

    private ViewDtoRenderer() {
    }

    static GeneratedFile render(GenerationContext ctx, SpringArtifact artifact, ViewDeclaration decl) {
        String packageName = artifact.packageName();
        ImportSorter imports = new ImportSorter();

        for (ViewField field : decl.fields()) {
            ctx.collectTypeImports(imports, field.type(), packageName);
        }

        String body = renderBody(decl);
        String content = GenerationContext.assembleSource(packageName, imports, body);
        String path = GenerationContext.javaPath(packageName, artifact.simpleName());
        LoweredNodeId artifactId = artifact.id();
        Optional<SymbolId> symbolId = Optional.of(decl.sourceSymbol());
        return new GeneratedFile(path, content, artifactId, symbolId);
    }

    private static String renderBody(ViewDeclaration decl) {
        StringBuilder out = new StringBuilder();
        out.append("public class ").append(decl.javaName()).append(" {\n\n");

        for (ViewField field : decl.fields()) {
            out.append("    private final ").append(TypeRenderer.renderBoxedType(field.type()))
                    .append(' ').append(field.javaName()).append(";\n\n");
        }

        out.append("    public ").append(decl.javaName()).append('(');
        for (int i = 0; i < decl.fields().size(); i++) {
            ViewField field = decl.fields().get(i);
            if (i > 0) {
                out.append(", ");
            }

            out.append(TypeRenderer.renderBoxedType(field.type())).append(' ').append(field.javaName());
        }

        out.append(") {\n");
        for (ViewField field : decl.fields()) {
            out.append("        this.").append(field.javaName()).append(" = ").append(field.javaName()).append(";\n");
        }

        out.append("    }\n\n");

        for (ViewField field : decl.fields()) {
            String capitalised = capitalise(field.javaName());
            out.append("    public ").append(TypeRenderer.renderBoxedType(field.type()))
                    .append(" get").append(capitalised).append("() {\n");
            out.append("        return this.").append(field.javaName()).append(";\n");
            out.append("    }\n\n");
        }

        out.append("}\n");
        return out.toString();
    }

    private static String capitalise(String value) {
        if (value.isEmpty()) {
            return value;
        }

        int first = value.codePointAt(0);
        String head = new String(Character.toChars(Character.toUpperCase(first)));
        return head + value.substring(Character.charCount(first));
    }
}

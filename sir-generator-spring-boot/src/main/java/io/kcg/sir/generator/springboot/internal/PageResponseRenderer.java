package io.kcg.sir.generator.springboot.internal;

import io.kcg.sir.generator.springboot.api.GeneratedFile;
import io.kcg.sir.lowering.springboot.model.ProjectArtifact.PageResponse;
import java.util.Optional;

/**
 * Renders the generic page envelope returned by paged queries.
 *
 * <p>The envelope is a response contract: a total count, the page and size that were actually served,
 * and the projected records.
 */
final class PageResponseRenderer {

    private PageResponseRenderer() {
    }

    static GeneratedFile render(PageResponse page) {
        ImportSorter imports = new ImportSorter();
        imports.add("java.util.List");
        String content = GenerationContext.assembleSource(page.packageName(), imports, renderBody(page));
        return new GeneratedFile(page.path(), content, page.id(), Optional.empty());
    }

    private static String renderBody(PageResponse page) {
        String name = page.simpleName();
        StringBuilder out = new StringBuilder();
        out.append("public class ").append(name).append("<T> {\n\n");
        out.append("    private final long total;\n\n");
        out.append("    private final int page;\n\n");
        out.append("    private final int size;\n\n");
        out.append("    private final List<T> records;\n\n");
        out.append("    public ").append(name).append("(long total, int page, int size, List<T> records) {\n");
        out.append("        this.total = total;\n");
        out.append("        this.page = page;\n");
        out.append("        this.size = size;\n");
        out.append("        this.records = List.copyOf(records);\n");
        out.append("    }\n\n");
        out.append("    public long getTotal() {\n");
        out.append("        return this.total;\n");
        out.append("    }\n\n");
        out.append("    public int getPage() {\n");
        out.append("        return this.page;\n");
        out.append("    }\n\n");
        out.append("    public int getSize() {\n");
        out.append("        return this.size;\n");
        out.append("    }\n\n");
        out.append("    public List<T> getRecords() {\n");
        out.append("        return this.records;\n");
        out.append("    }\n");
        out.append("}\n");
        return out.toString();
    }
}

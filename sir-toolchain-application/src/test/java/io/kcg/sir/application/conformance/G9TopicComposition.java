package io.kcg.sir.application.conformance;

import io.kcg.sir.ast.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/**
 * Keeps the complete actual library, adding only the actual market's Item slice: its write policy and its public browse
 * query, whose filter on the reserved column {@code condition} must run on the real database.
 */
public final class G9TopicComposition {
    private G9TopicComposition() {}

    static String compose() throws Exception {
        String library = ConformanceFixtures.readResource("/topics/library/project.sir").replace("\r\n", "\n");
        String market = ConformanceFixtures.readResource("/topics/market/project.sir").replace("\r\n", "\n");
        var selected = ShopSourceComposition.parse(market).declarations().stream().filter(G9TopicComposition::isMarketSlice).toList();
        if (selected.size() != 5) throw new IllegalStateException("expected canonical ItemStatus, Item, its Crud use and the browse query");
        String addition = String.join("\n", selected.stream().map(d -> fragment(market, d)).toList());
        var first = ShopSourceComposition.parse(library).declarations().getFirst();
        int offset = library.offsetByCodePoints(0, first.span().start().codePointOffset());
        return library.substring(0, offset) + addition + "\n" + library.substring(offset);
    }

    private static boolean isMarketSlice(AstDeclaration d) {
        return d instanceof AstEnumDecl enumeration && enumeration.name().text().equals("ItemStatus")
                || d instanceof AstEntityDecl e && e.name().text().equals("Item")
                || d instanceof AstFeatureUseDecl use && use.name().text().equals("Crud")
                    && use.arguments().stream().anyMatch(a -> a.parameter().text().equals("entity") && a.value().text().equals("Item"))
                || d instanceof AstInputDecl input && input.name().text().equals("BrowseInput")
                || d instanceof AstCapabilityDecl capability && capability.name().text().equals("BrowseItems");
    }

    private static String fragment(String source, AstDeclaration declaration) {
        return source.substring(source.offsetByCodePoints(0, declaration.span().start().codePointOffset()),
                source.offsetByCodePoints(0, declaration.span().end().codePointOffset())).strip();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("one destination .sir path required");
        Files.writeString(Path.of(args[0]), compose(), StandardCharsets.UTF_8);
    }
}

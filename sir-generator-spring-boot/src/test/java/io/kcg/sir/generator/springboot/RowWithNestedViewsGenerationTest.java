package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.generator.springboot.api.GeneratedFile;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A returned row that the response view nests other views into is projected through one batch read per association, the
 * way a page of rows is; a view with nothing nested is built straight from the row.
 */
class RowWithNestedViewsGenerationTest {
    @Test void aLoadedOrCreatedRowIsProjectedThroughBatchReadsOfItsAssociations() {
        List<GeneratedFile> files = GeneratorTestSupport.generateSuccess("valid/row-with-nested-views.sir");
        for (String service : new String[] {"GetBookService", "AddBookService"}) {
            String java = content(files, service);
            assertTrue(java.contains("var returnedRows = java.util.List.of("), service + "\n" + java.substring(java.indexOf("public")));
            assertTrue(java.contains("authorMapper.selectList") && java.contains("reviewMapper.selectList"), service);
            assertTrue(java.contains("new BookDetail(") && java.contains(".orElse(null)") && java.contains("getOrDefault("), service);
        }
        String card = content(files, "GetBookCardService");
        assertFalse(card.contains("returnedRows"), "nothing nested, nothing to read");
        assertTrue(card.contains("new BookCard(book.getId(), book.getTitle())"), card);
    }

    private static String content(List<GeneratedFile> files, String service) {
        return files.stream().filter(f -> f.relativePath().endsWith("/" + service + ".java")).findFirst().orElseThrow().content();
    }
}

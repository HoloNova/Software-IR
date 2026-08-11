package io.kcg.sir.generator.springboot.internal;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ImportSorterTest {

    @Test
    void caseDistinctFullyQualifiedNamesAreNotDeduplicated() {
        ImportSorter sorter = new ImportSorter();
        sorter.add("example.domain.Foo");
        sorter.add("example.domain.foo");

        String rendered = sorter.render();

        assertTrue(rendered.contains("import example.domain.Foo;"), rendered);
        assertTrue(rendered.contains("import example.domain.foo;"), rendered);
    }
}

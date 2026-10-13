package io.kcg.sir.change.edit;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Shared test sources for the structural edit tests. */
final class EditFixtures {
    private EditFixtures() {
    }

    /** A small course catalog: patch update with a version field, a paged query with a relation filter. */
    static String courseCatalog() {
        try (InputStream stream = EditFixtures.class.getResourceAsStream("/edit/course-catalog.sir")) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}

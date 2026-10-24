package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.semantic.api.ReferenceRole;
import io.kcg.sir.semantic.api.SirSemanticAnalyzer;
import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourceSnapshot;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The counted entity and the field that joins it to the source row are both references, and both must be
 * visible from the file that writes the count. Read-only analysis: no generated project and no database.
 */
class RelatedCountProjectTest {
    @TempDir Path temp;

    @Test void aCountReadsAnImportedChildAndRefusesToGuessWhenTheImportIsGone() throws Exception {
        Path source = ProjectTestSupport.fixture(temp);
        ProjectVisibilityTest.addRelatedCount(source);
        var snapshot = snapshot(source);
        var positive = new SirSemanticAnalyzer().analyzeProject(ProjectTestSupport.parse(snapshot));
        assertTrue(positive.isSuccess(), () -> positive.diagnostics().toString());
        var model = positive.model().orElseThrow();
        Set<ReferenceRole> roles = EnumSet.noneOf(ReferenceRole.class);
        for (var binding : model.referenceSiteBindings().all())
            if (binding.site().span().source().equals(ProjectTestSupport.ENTRY)) roles.add(binding.site().role());
        assertTrue(roles.containsAll(Set.of(ReferenceRole.VIEW_COUNT_ENTITY, ReferenceRole.VIEW_RELATION_FIELD)), roles.toString());

        Path root = source.resolve("project.sir");
        String original = Files.readString(root);
        String imported = "import Enrollment from \"modules/enrollment.sir\";";
        assertTrue(original.contains(imported));
        Files.writeString(root, original.replace(imported, " ".repeat(imported.length())));
        var negative = new SirSemanticAnalyzer().analyzeProject(ProjectTestSupport.parse(snapshot(source)));
        assertFalse(negative.isSuccess());
        var errors = negative.diagnostics().stream().filter(d -> d.isError()).toList();
        assertTrue(errors.stream().allMatch(d -> d.code().value().equals("SIR-SOURCE-VISIBILITY-001")), errors::toString);
        assertTrue(errors.stream().allMatch(d -> d.primarySpan().source().equals(ProjectTestSupport.ENTRY)), errors::toString);
    }

    private static SourceSnapshot snapshot(Path root) throws Exception {
        Map<SourceId, byte[]> inputs = new LinkedHashMap<>();
        for (String file : ProjectTestSupport.FILES) inputs.put(SourceId.of(file), Files.readAllBytes(root.resolve(file)));
        return new SourceSnapshot(ProjectTestSupport.ENTRY, inputs);
    }
}

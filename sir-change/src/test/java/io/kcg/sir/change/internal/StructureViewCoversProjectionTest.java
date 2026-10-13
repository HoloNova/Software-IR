package io.kcg.sir.change.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.api.ParseResult;
import io.kcg.sir.api.SirParser;
import io.kcg.sir.api.SirSource;
import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import io.kcg.sir.semantic.api.SemanticAnalysis;
import io.kcg.sir.semantic.api.SirSemanticAnalyzer;
import io.kcg.sir.semantic.schema.StructureView;
import io.kcg.sir.source.SourceId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The semantic layer's structure view must see at least everything the change layer's hand-written
 * {@link SemanticProjection} sees, and more where that projection has gaps.
 *
 * <p>This is what lets the projection be retired later: for every edit below, "the old projection
 * noticed a change" implies "the new view noticed a change". Edits the old projection misses are
 * listed separately so the gap stays on record.
 */
class StructureViewCoversProjectionTest {
    private record Edit(String name, String from, String to) {
    }

    private static List<Edit> edits() {
        return List.of(
            new Edit("validate condition", "validate input.name.present else EmptyChange;", "validate input.name.present or input.capacity.present else EmptyChange;"),
            new Edit("relation condition", "status == EnrollmentStatus.ACTIVE", "status == EnrollmentStatus.CANCELLED"),
            new Edit("order key and direction", "          order by code ascending", "          order by name descending"),
            new Edit("page clause", "Page input.page, input.size else InvalidPage", "Page input.page, input.sizeAlt else InvalidPage"),
            new Edit("constraint argument", "length(1, 32)", "length(1, 64)"),
            new Edit("view field removed", "field studentNo: String;\n      field name: String;\n    }\n\n    view StudentSummaryAlt", "field studentNo: String;\n    }\n\n    view StudentSummaryAlt")
        );
    }

    @Test
    void whenTheOldProjectionNoticesAChangeTheStructureViewNoticesItToo() throws Exception {
        String base = SemanticProjectionParityTest.template();
        List<String> checked = new ArrayList<>();

        for (Edit edit : edits()) {
            assertTrue(base.contains(edit.from()), "edit anchor missing: " + edit.name());
            String changed = base.replace(edit.from(), edit.to());
            NormalizedSemanticModel before = compile(base);
            NormalizedSemanticModel after = compile(changed);

            boolean oldSees = !projections(before).equals(projections(after));
            boolean newSees = !StructureView.facts(before).equals(StructureView.facts(after));

            if (oldSees) {
                assertTrue(newSees, "the old projection saw '" + edit.name() + "' but the structure view did not");
            }

            checked.add(edit.name() + " old=" + oldSees + " new=" + newSees);
        }

        assertFalse(checked.isEmpty());
    }

    @Test
    void errorHttpStatusIsInTheStructureViewButMissingFromTheOldProjection() throws Exception {
        String base = SemanticProjectionParityTest.template();
        String changed = base.replace("error CourseNotFound;", "error CourseNotFound http 404;");
        assertNotEquals(base, changed);

        NormalizedSemanticModel before = compile(base);
        NormalizedSemanticModel after = compile(changed);

        assertEquals(projections(before), projections(after), "documents the known gap in the hand-written projection (Error.httpStatus)");
        assertNotEquals(StructureView.facts(before), StructureView.facts(after), "the semantic structure sees the status code");
    }

    @Test
    void formattingOnlyEditsAreInvisibleToBoth() throws Exception {
        String base = SemanticProjectionParityTest.template();
        String reflowed = "// header\n" + base.replace("fails CourseNotFound;", "fails   CourseNotFound ;   // trailing");

        assertEquals(projections(compile(base)), projections(compile(reflowed)));
        assertEquals(StructureView.facts(compile(base)), StructureView.facts(compile(reflowed)));
    }

    private static List<String> projections(NormalizedSemanticModel model) {
        return model.declarations().stream().map(d -> SemanticProjection.ofFullDeclaration(d).toString()).toList();
    }

    private static NormalizedSemanticModel compile(String source) {
        ParseResult parseResult = SirParser.create().parse(new SirSource(SourceId.of("structure-parity.sir"), source));
        assertTrue(parseResult.isSuccess(), () -> "source must parse: " + parseResult.diagnostics());
        SemanticAnalysis analysis = new SirSemanticAnalyzer().analyze(parseResult.document().orElseThrow());
        assertFalse(analysis.hasErrors(), () -> "source must analyze: " + analysis.diagnostics());
        return assertInstanceOf(SemanticAnalysis.Success.class, analysis).normalizedModel();
    }
}

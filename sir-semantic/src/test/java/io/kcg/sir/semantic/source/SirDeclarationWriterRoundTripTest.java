package io.kcg.sir.semantic.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.semantic.TestSources;
import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import io.kcg.sir.semantic.api.SemanticAnalysis;
import io.kcg.sir.semantic.model.NormalizedDeclaration;
import io.kcg.sir.semantic.schema.SoftwareDraft;
import io.kcg.sir.semantic.schema.StructNode;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The writer is only correct if what it writes means the same thing when read back.
 *
 * <p>For every valid source the semantic tests own, each declaration is rewritten from its draft,
 * put back where the original was, and the whole file goes through the formal analysis again. The
 * authored draft of the result must equal the authored draft of the original, identities included.
 */
class SirDeclarationWriterRoundTripTest {
    private static final List<String> RESOURCES = List.of(
        "valid/explicit-lock.sir", "valid/decimal-group.sir", "valid/decimal-sum.sir", "valid/scalar-queries.sir", "valid/optional-values.sir", "valid/all-workflow-steps.sir", "valid/campus-market.sir", "valid/course-admin.sir", "valid/course-enrollment.sir", "valid/bearer-credential.sir", "valid/dual-token-feature.sir", "valid/semantic-types.sir", "valid/user-feature.sir", "valid/delete-step.sir", "valid/default-value.sir", "valid/entity-scope.sir", "valid/entity-extension.sir", "valid/guarded-update.sir", "valid/secret-field.sir", "valid/time-arithmetic.sir", "valid/unique-lookup.sir", "valid/unit-capability.sir", "valid/unique-key.sir"
    );

    @Test
    void rewritingEveryDeclarationOfEveryValidSourceKeepsTheSoftware() throws Exception {
        int declarations = 0;
        List<String> sources = validSources();
        for (String source : sources) {
            NormalizedSemanticModel before = TestSources.analyze(source).model().orElseThrow();
            String rewritten = rewriteAll(source, before);

            SemanticAnalysis analysis = TestSources.analyze(rewritten);
            assertTrue(analysis.isSuccess(), "the rewritten source must stay valid: " + analysis.diagnostics() + "\n" + rewritten);
            assertEquals(
                SoftwareDraft.of(before).root().render(),
                SoftwareDraft.of(analysis.model().orElseThrow()).root().render(),
                "the rewritten source changed the software:\n" + rewritten
            );
            declarations += before.declarations().size();
        }

        assertTrue(sources.size() >= 8, "the check should run over many sources, got " + sources.size());
        assertTrue(declarations >= 40, "and over many declarations, got " + declarations);
    }

    @Test
    void aStringTheLanguageCannotSpellIsRefusedNotAltered() {
        NormalizedSemanticModel model = TestSources.analyze(TestSources.resource("valid/course-admin.sir")).model().orElseThrow();
        StructNode capability = SoftwareDraft.of(model).declaration("NormalizedCapability", "GetCourse").orElseThrow();

        // a draft whose declaration kind is not a declaration cannot be written at all
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> new SirDeclarationWriter("").write(new StructNode("NameExpression", List.of())));
        assertTrue(new SirDeclarationWriter("    ").write(capability).startsWith("capability GetCourse {\n"));
    }

    private static String rewriteAll(String source, NormalizedSemanticModel model) {
        SoftwareDraft draft = SoftwareDraft.of(model);
        // Only what the author wrote is source text; what a use declaration expanded into shares its span.
        List<NormalizedDeclaration> byPosition = new ArrayList<>(model.declarations().stream().filter(model::isWritten).toList());
        byPosition.sort(Comparator.comparingInt((NormalizedDeclaration d) -> d.span().start().codePointOffset()).reversed());

        // work from the back so earlier offsets stay valid; offsets count code points
        String current = source;
        for (NormalizedDeclaration declaration : byPosition) {
            StructNode node = draft.declarations().stream()
                .filter(d -> SoftwareDraft.identityOf(d).orElse("").equals(declaration.id().value()))
                .findFirst()
                .orElseThrow();
            int start = declaration.span().start().codePointOffset();
            int end = declaration.span().end().codePointOffset();
            String indent = " ".repeat(declaration.span().start().column() - 1);
            String written = new SirDeclarationWriter(indent).write(node);
            int[] now = current.codePoints().toArray();
            current = new String(now, 0, start) + written + new String(now, end, now.length - end);
        }

        return current;
    }

    private static List<String> validSources() throws Exception {
        List<String> sources = new ArrayList<>();
        for (String resource : RESOURCES) {
            sources.add(TestSources.resource(resource));
        }

        for (Field field : TestSources.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class && Modifier.isPublic(field.getModifiers())) {
                String text = (String)field.get(null);
                try {
                    if (TestSources.analyze(text).isSuccess()) {
                        sources.add(text);
                    }
                } catch (AssertionError notASource) {
                    // a constant that is not a parseable source is not a candidate
                }
            }
        }

        return sources;
    }
}

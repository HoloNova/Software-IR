package io.kcg.sir.parser;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.api.ParseResult;
import io.kcg.sir.api.SirSource;
import io.kcg.sir.ast.AstCapabilityDecl;
import io.kcg.sir.ast.AstCreateStep;
import io.kcg.sir.ast.AstFindStep;
import io.kcg.sir.ast.AstLoadStep;
import io.kcg.sir.ast.AstPersistStep;
import io.kcg.sir.ast.AstReturnStep;
import io.kcg.sir.ast.AstStep;
import io.kcg.sir.ast.AstUpdateStep;
import io.kcg.sir.ast.AstValidateStep;
import io.kcg.sir.internal.DefaultSirParser;
import io.kcg.sir.source.SourceId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkflowAstShapeTest {
    private final DefaultSirParser parser = new DefaultSirParser();

    @Test
    void goldFilesTogetherCoverEveryWorkflowStepKind() {
        List<AstStep> steps = List.of(
                        capability("valid/campus-market.sir"),
                        capability("valid/all-workflow-steps.sir"))
                .stream()
                .flatMap(capability -> capability.workflow().steps().stream())
                .toList();

        assertAll(
                () -> assertContains(steps, AstValidateStep.class),
                () -> assertContains(steps, AstLoadStep.class),
                () -> assertContains(steps, AstFindStep.class),
                () -> assertContains(steps, AstCreateStep.class),
                () -> assertContains(steps, AstUpdateStep.class),
                () -> assertContains(steps, AstPersistStep.class),
                () -> assertContains(steps, AstReturnStep.class));
    }

    @Test
    void campusMarketPreservesWorkflowOrderAndPersistTarget() {
        List<AstStep> steps = capability("valid/campus-market.sir").workflow().steps();

        assertEquals(
                List.of(AstValidateStep.class, AstCreateStep.class, AstPersistStep.class, AstReturnStep.class),
                steps.stream().map(Object::getClass).toList());
        AstPersistStep persist = assertInstanceOf(AstPersistStep.class, steps.get(2));
        assertEquals("goods", persist.target().text());
    }

    @Test
    void workflowSamplePreservesOrderAndCoreStepFields() {
        List<AstStep> steps = capability("valid/all-workflow-steps.sir").workflow().steps();

        assertEquals(
                List.of(
                        AstLoadStep.class,
                        AstUpdateStep.class,
                        AstPersistStep.class,
                        AstFindStep.class,
                        AstReturnStep.class),
                steps.stream().map(Object::getClass).toList());

        AstLoadStep load = assertInstanceOf(AstLoadStep.class, steps.get(0));
        AstPersistStep persist = assertInstanceOf(AstPersistStep.class, steps.get(2));
        AstFindStep find = assertInstanceOf(AstFindStep.class, steps.get(3));
        assertAll(
                () -> assertEquals("User", load.entity().text()),
                () -> assertEquals("user", load.result().text()),
                () -> assertEquals("NotFound", load.error().text()),
                () -> assertEquals("user", persist.target().text()),
                () -> assertEquals("User", find.entity().text()),
                () -> assertEquals("users", find.result().text()));
    }

    private AstCapabilityDecl capability(String path) {
        ParseResult result = parser.parse(new SirSource(SourceId.of("tests/" + path), resource(path)));
        assertTrue(result.isSuccess(), result.diagnostics().toString());
        return result.document().orElseThrow().software().declarations().stream()
                .filter(AstCapabilityDecl.class::isInstance)
                .map(AstCapabilityDecl.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private static void assertContains(List<AstStep> steps, Class<? extends AstStep> type) {
        assertTrue(steps.stream().anyMatch(type::isInstance), () -> "missing workflow step: " + type.getSimpleName());
    }

    private static String resource(String path) {
        try (var stream = WorkflowAstShapeTest.class.getResourceAsStream("/" + path)) {
            if (stream == null) {
                throw new IllegalArgumentException("missing test resource: " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}

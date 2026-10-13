package io.kcg.sir.semantic.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.semantic.model.NormalizedDeclaration;
import io.kcg.sir.semantic.model.NormalizedExpression;
import io.kcg.sir.semantic.model.NormalizedStep;
import io.kcg.sir.semantic.type.SirType;
import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The schema is only useful if nothing can sit outside it. These tests pin the three ways a new
 * model element could escape: an unclassified component, a sealed variant nobody registered, and
 * a record added to a model package without being reachable from the model root.
 */
class SoftwareSchemaCompletenessTest {

    @Test
    void normalizedModelSchemaBuildsWithEveryComponentClassified() {
        SoftwareSchema schema = SoftwareSchema.normalizedModel();

        assertEquals("NormalizedSemanticModel", schema.root().kind());
        assertTrue(schema.nodes().size() > 40, "the model has dozens of node kinds, got " + schema.nodes().size());
    }

    @Test
    void everySealedVariantIsRegistered() {
        SoftwareSchema schema = SoftwareSchema.normalizedModel();

        for (Class<?> sealed : List.of(NormalizedDeclaration.class, NormalizedStep.class, NormalizedExpression.class, SirType.class)) {
            List<Class<?>> permitted = schema.unions().get(sealed);
            assertEquals(List.of(sealed.getPermittedSubclasses()), permitted, sealed.getSimpleName() + " variants");
            for (Class<?> variant : permitted) {
                assertTrue(schema.nodes().containsKey(variant), variant.getName() + " is a variant of " + sealed.getSimpleName() + " but not a schema node");
            }
        }
    }

    @Test
    void everyRecordInTheModelPackagesIsReachableFromTheRoot() throws Exception {
        SoftwareSchema schema = SoftwareSchema.normalizedModel();
        List<String> orphans = new ArrayList<>();

        for (String pkg : List.of("io/kcg/sir/semantic/model", "io/kcg/sir/semantic/type")) {
            URL location = getClass().getClassLoader().getResource(pkg);
            File directory = directoryOf(location);
            for (File file : directory.listFiles((dir, name) -> name.endsWith(".class"))) {
                String className = pkg.replace('/', '.') + "." + file.getName().replace(".class", "");
                Class<?> type = Class.forName(className);
                if (type.isRecord() && !schema.nodes().containsKey(type) && !isPayloadOfDerivedSlot(schema, type)) {
                    orphans.add(className);
                }
            }
        }

        assertTrue(orphans.isEmpty(), "records that the software schema cannot reach: " + orphans);
    }

    @Test
    void unclassifiedComponentIsRejectedByName() {
        SoftwareSchema.SchemaViolation violation = assertThrows(SoftwareSchema.SchemaViolation.class, () -> SoftwareSchema.of(Unclassified.class));

        assertEquals(1, violation.problems().size());
        assertTrue(violation.problems().get(0).contains("Unclassified.forgotten"), violation.getMessage());
    }

    @Test
    void doubleClassificationIsRejected() {
        SoftwareSchema.SchemaViolation violation = assertThrows(SoftwareSchema.SchemaViolation.class, () -> SoftwareSchema.of(Doubled.class));

        assertTrue(violation.problems().get(0).contains("more than one classification"), violation.getMessage());
    }

    @Test
    void typeTheSchemaCannotDescribeMustBeMarkedDerived() {
        SoftwareSchema.SchemaViolation violation = assertThrows(SoftwareSchema.SchemaViolation.class, () -> SoftwareSchema.of(WithMap.class));

        assertTrue(violation.problems().get(0).contains("WithMap.lookup"), violation.getMessage());
        // the same component is fine once the author says it is recomputed analysis
        SoftwareSchema.of(WithDerivedMap.class);
    }

    @Test
    void identityAndReferenceMustHoldASymbolId() {
        SoftwareSchema.SchemaViolation violation = assertThrows(SoftwareSchema.SchemaViolation.class, () -> SoftwareSchema.of(IdentityIsText.class));

        assertTrue(violation.problems().get(0).contains("must hold a SymbolId"), violation.getMessage());
    }

    @Test
    void keyedCollectionNeedsAnIdentityOnEveryElementKind() {
        SoftwareSchema.SchemaViolation violation = assertThrows(SoftwareSchema.SchemaViolation.class, () -> SoftwareSchema.of(KeyedWithoutIdentity.class));

        assertTrue(violation.problems().get(0).contains("has no @Identity slot"), violation.getMessage());
    }

    /** A record used only inside analysis output (a derived slot) is deliberately not described. */
    private static boolean isPayloadOfDerivedSlot(SoftwareSchema schema, Class<?> type) {
        return schema.nodes().values().stream()
            .flatMap(node -> node.slots().stream())
            .anyMatch(slot -> slot.kind() == SlotKind.DERIVED && slot.valueType() == type);
    }

    private static File directoryOf(URL location) throws URISyntaxException {
        assertEquals("file", location.getProtocol(), "model classes are scanned from target/classes");
        return new File(location.toURI());
    }

    record Unclassified(@Fact String named, String forgotten) {
    }

    record Doubled(@Fact @Source String both) {
    }

    record WithMap(@Fact Map<String, String> lookup) {
    }

    record WithDerivedMap(@Derived Map<String, String> lookup, @Fact String name) {
    }

    record IdentityIsText(@Identity String id) {
    }

    record Element(@Fact String name) {
    }

    record KeyedWithoutIdentity(@Fact @Keyed java.util.List<Element> items) {
    }
}

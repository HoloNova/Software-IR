package io.kcg.sir.semantic.schema;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.semantic.TestSources;
import io.kcg.sir.semantic.api.SemanticAnalysis;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * A schema entry that no real source ever produces is an entry nobody has tested. This gate
 * reads every valid source the semantic tests own and requires each node kind in the schema to be
 * produced by at least one of them.
 */
class StructureViewCoverageTest {
    private static final List<String> RESOURCES = List.of(
        "valid/all-workflow-steps.sir", "valid/campus-market.sir", "valid/course-admin.sir", "valid/course-enrollment.sir", "valid/bearer-credential.sir", "valid/dual-token-feature.sir", "valid/semantic-types.sir", "valid/user-feature.sir", "valid/guarded-update.sir", "valid/secret-field.sir", "valid/time-arithmetic.sir", "valid/unique-lookup.sir", "valid/unit-capability.sir"
    );

    @Test
    void everyNodeKindInTheSchemaIsProducedByAtLeastOneValidSource() throws Exception {
        Set<String> seen = new TreeSet<>();
        for (String source : validSources()) {
            collect(StructureView.withSource(TestSources.analyze(source).model().orElseThrow()), seen);
        }

        Set<String> missing = new TreeSet<>();
        for (NodeType node : SoftwareSchema.normalizedModel().nodes().values()) {
            if (!seen.contains(node.kind())) {
                missing.add(node.kind());
            }
        }

        assertTrue(missing.isEmpty(), "node kinds no valid test source exercises: " + missing);
    }

    private static List<String> validSources() throws Exception {
        List<String> sources = new ArrayList<>();
        sources.add(literalsAndUnary());
        for (String resource : RESOURCES) {
            sources.add(TestSources.resource(resource));
        }

        for (Field field : TestSources.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class && Modifier.isPublic(field.getModifiers())) {
                String text = (String)field.get(null);
                try {
                    SemanticAnalysis analysis = TestSources.analyze(text);
                    if (analysis.isSuccess()) {
                        sources.add(text);
                    }
                } catch (AssertionError parseFailure) {
                    // a constant that is not a parseable source is not a coverage candidate
                }
            }
        }

        return sources;
    }

    /** Boolean and string literals and a unary operator, which none of the shared fixtures spell. */
    private static String literalsAndUnary() {
        String source = TestSources.resource("valid/all-workflow-steps.sir")
            .replace("field state: State;", "field state: State;\n      field name: Text;")
            .replace("load User by input.id", "validate not input.active else NotFound;\n        validate input.active == true else NotFound;\n        load User by input.id")
            .replace("item.state == State.ACTIVE", "item.state == State.ACTIVE and item.name == \"abc\"");
        assertTrue(TestSources.analyze(source).isSuccess(), "coverage source must stay valid");
        return source;
    }

    private static void collect(StructNode node, Set<String> seen) {
        seen.add(node.kind());
        for (StructNode.Slot slot : node.slots()) {
            collect(slot.value(), seen);
        }
    }

    private static void collect(StructValue value, Set<String> seen) {
        switch (value) {
            case StructValue.Child child -> collect(child.node(), seen);
            case StructValue.Seq seq -> seq.elements().forEach(e -> collect(e, seen));
            case StructValue.Text text -> {
            }
            case StructValue.None none -> {
            }
        }
    }
}

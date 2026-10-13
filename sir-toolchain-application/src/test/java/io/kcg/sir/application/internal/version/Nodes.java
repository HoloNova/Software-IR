package io.kcg.sir.application.internal.version;

import io.kcg.sir.semantic.schema.SlotKind;
import io.kcg.sir.semantic.schema.StructNode;
import io.kcg.sir.semantic.schema.StructValue;
import java.util.ArrayList;
import java.util.List;

/** Spells draft nodes by hand in tests; it knows nothing about the language, only how a node is shaped. */
final class Nodes {
    private Nodes() {
    }

    static final SlotKind FACT = SlotKind.FACT;
    static final SlotKind REF = SlotKind.REFERENCE;

    static StructValue text(String value) {
        return new StructValue.Text(value);
    }

    static StructValue none() {
        return new StructValue.None();
    }

    static StructValue child(StructNode node) {
        return new StructValue.Child(node);
    }

    static StructValue seq(StructValue... elements) {
        return new StructValue.Seq(List.of(elements));
    }

    static StructValue seqOf(List<StructNode> nodes) {
        return new StructValue.Seq(nodes.stream().<StructValue>map(StructValue.Child::new).toList());
    }

    /** A node whose slots are given as key, role, value triples. */
    static StructNode node(String kind, Object... triples) {
        List<StructNode.Slot> slots = new ArrayList<>();
        for (int i = 0; i < triples.length; i += 3) {
            slots.add(new StructNode.Slot((String)triples[i], (SlotKind)triples[i + 1], (StructValue)triples[i + 2]));
        }

        return new StructNode(kind, slots);
    }

    static StructNode primitive(String constant) {
        return node("PrimitiveType", "constant", FACT, text(constant));
    }

    static StructNode constraint(String name) {
        return node("NormalizedConstraint", "name", FACT, text(name), "arguments", FACT, seq());
    }

    static StructNode field(String name, StructNode type, StructNode... constraints) {
        return node("NormalizedField", "name", FACT, text(name), "type", FACT, child(type), "constraints", FACT, seqOf(List.of(constraints)), "versioned", FACT, text("false"));
    }

    static StructNode input(String name, StructNode... fields) {
        return node("NormalizedInput", "name", FACT, text(name), "kind", FACT, text("PLAIN"), "patchSourceEntity", REF, none(), "fields", FACT, seqOf(List.of(fields)));
    }

    static StructNode name(String name) {
        return node("NameExpression", "name", FACT, text(name));
    }

    static StructNode member(StructNode receiver, String member) {
        return node("MemberExpression", "receiver", FACT, child(receiver), "member", FACT, text(member));
    }

    static StructNode binary(StructNode left, String operator, StructNode right) {
        return node("BinaryExpression", "left", FACT, child(left), "operator", FACT, text(operator), "right", FACT, child(right));
    }

    static StructNode find(String entity, StructNode predicate, String result) {
        return node("FindStep", "entitySymbol", REF, text(entity), "predicate", FACT, child(predicate), "orderKeys", FACT, seq(), "page", FACT, none(), "resultVariable", REF, text(result));
    }

    static StructNode returnStep(StructNode value) {
        return node("ReturnStep", "value", FACT, child(value));
    }

    static StructNode listOfRef(String entity) {
        return node("ListType", "element", FACT, child(node("RefType", "entityName", FACT, text(entity))));
    }

    /** A read-only query capability with no actor, errors or paging. */
    static StructNode queryCapability(String name, String inputName, StructNode output, StructNode... steps) {
        return node("NormalizedCapability", "name", FACT, text(name), "actorSymbol", REF, none(), "inputSymbol", REF, text(inputName), "outputType", FACT, child(output),
            "fails", REF, seq(), "requires", FACT, seq(text("READONLY")), "exposure", FACT, text("QUERY"),
            "workflow", FACT, child(node("NormalizedWorkflow", "steps", FACT, seqOf(List.of(steps)))));
    }
}

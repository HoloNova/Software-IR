package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.*;
import static io.kcg.sir.application.MultiSourceTestSupport.*;
import io.kcg.sir.ast.*;
import io.kcg.sir.application.api.*;
import io.kcg.sir.projectgraph.api.*;
import io.kcg.sir.semantic.api.*;
import io.kcg.sir.source.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MultiSourceGenerationTest {
    @TempDir Path temp;

    @Test void realCourseProjectProducesExactlyTheSingleSourceApplicationAndRealProvenance() throws Exception {
        Path source = fixture(temp), output = temp.resolve("multi-output");
        Files.writeString(source.resolve("unlisted.sir"), "not a valid source, must never be discovered");
        var multi = generate(source, output);
        Path singleFile = temp.resolve("single.sir"), singleOutput = temp.resolve("single-output");
        Files.writeString(singleFile, resource("valid/course-admin-enrollment.sir"));
        var single = assertInstanceOf(ToolchainResult.Success.class, new ToolchainApplication().execute(
                new ToolchainRequest(singleFile, ENTRY, singleOutput, ConflictPolicy.FAIL_IF_EXISTS)));
        assertEquals(files(singleOutput), files(output));
        assertEquals(single.manifest().files().size(), multi.manifest().files().size());
        assertEquals(4, multi.sources().manifest().files().size());
        for (var entry : multi.sources().manifest().files()) assertArrayEquals(Files.readAllBytes(source.resolve(entry.sourceId().value())), multi.sources().bytes(entry.sourceId()));
        assertFalse(multi.sources().manifest().contains(SourceId.of("unlisted.sir")));
        var course = multi.graph().nodes().stream().filter(n -> n instanceof ProjectGraphNode.SemanticDeclaration d && d.displayName().equals("Course"))
                .map(n -> (ProjectGraphNode.SemanticDeclaration) n).findFirst().orElseThrow();
        assertEquals(SourceId.of("modules/course.sir"), course.provenance().sourceId());
        assertEquals(course.provenance().sourceId(), course.provenance().span().source());
        var encoded = assertInstanceOf(ProjectGraphSerialization.Success.class, new ProjectGraphSerializer().serialize(multi.graph(), ProjectGraphCanonicalFormatVersion.V2));
        var restored = assertInstanceOf(ProjectGraphAnalysis.Success.class, new ProjectGraphLoader().load(encoded.document()));
        assertEquals(multi.graph().nodes(), restored.graph().nodes());
        assertEquals(multi.graph().canonicalDigest(), restored.graph().canonicalDigest());
        assertArrayEquals(encoded.document().bytes(), assertInstanceOf(ProjectGraphSerialization.Success.class,
                new ProjectGraphSerializer().serialize(restored.graph(), ProjectGraphCanonicalFormatVersion.V2)).document().bytes());
        assertFalse(Files.exists(temp.resolve("CURRENT"))); assertFalse(Files.exists(temp.resolve("state")));
        assertFalse(Files.exists(output.resolve("source.sir"))); // no concatenated-source product
    }

    @Test void sourceBytesNotDirectoryDiscoveryDriveTheSnapshotAndGraphEvidence() throws Exception {
        Path source = fixture(temp);
        var original = generate(source, temp.resolve("before"));
        Files.writeString(source.resolve("unlisted.sir"), "unlisted arbitrary bytes");
        var unchanged = generate(source, temp.resolve("unlisted"));
        assertEquals(original.sources().sha256Hex(), unchanged.sources().sha256Hex());
        assertEquals(original.graph().canonicalDigest(), unchanged.graph().canonicalDigest());
        Path course = source.resolve("modules/course.sir");
        Files.writeString(course, Files.readString(course) + "\n// comment changes evidence, not Java\n");
        var comment = generate(source, temp.resolve("comment"));
        assertNotEquals(original.sources().sha256Hex(), comment.sources().sha256Hex());
        assertNotEquals(original.graph().canonicalDigest(), comment.graph().canonicalDigest());
        assertEquals(files(temp.resolve("before")), files(temp.resolve("comment")));
        Path root = source.resolve("project.sir");
        Files.writeString(root, Files.readString(root).replace("source \"modules/course.sir\";\n    source \"modules/student.sir\";",
                "source \"modules/student.sir\";\n    source \"modules/course.sir\";"));
        var reorderedText = generate(source, temp.resolve("reordered-text"));
        assertNotEquals(comment.sources().sha256Hex(), reorderedText.sources().sha256Hex(), "changing the root source itself must not be hidden as list reordering");
        assertEquals(files(temp.resolve("comment")), files(temp.resolve("reordered-text")));
        var input = parse(comment.sources()); var reverse = new ArrayList<>(input.fragments()); Collections.reverse(reverse);
        var semantic = new SirSemanticAnalyzer().analyzeProject(new ProjectSemanticInput(input.root(), reverse));
        assertTrue(semantic.isSuccess(), semantic.diagnostics().toString());
        assertEquals(model(comment.sources()).declarations(), semantic.model().orElseThrow().declarations());
        Map<SourceId, byte[]> reversed = new LinkedHashMap<>();
        var entries = new ArrayList<>(comment.sources().manifest().files()); Collections.reverse(entries);
        entries.forEach(e -> reversed.put(e.sourceId(), comment.sources().bytes(e.sourceId())));
        assertEquals(comment.sources().sha256Hex(), new SourceSnapshot(ENTRY, reversed).sha256Hex());
    }

    @Test void movingExplicitCapabilityParsesItsNewLocationAndPreservesEveryTargetAndGeneratedByte() throws Exception {
        Path source = fixture(temp);
        var before = generate(source, temp.resolve("before"));
        var input = parse(before.sources());
        var capability = input.root().declarations().stream().filter(d -> d instanceof AstCapabilityDecl c && c.name().text().equals("SearchCourseEnrollments"))
                .map(d -> (AstCapabilityDecl) d).findFirst().orElseThrow();
        String root = new String(before.sources().bytes(ENTRY), StandardCharsets.UTF_8);
        int start = root.offsetByCodePoints(0, capability.span().start().codePointOffset()), end = root.offsetByCodePoints(0, capability.span().end().codePointOffset());
        String snippet = root.substring(start, end);
        root = root.substring(0, start) + root.substring(end);
        root = root.replace("sources {", "sources { source \"modules/search.sir\";");
        Files.writeString(source.resolve("project.sir"), root);
        Files.writeString(source.resolve("modules/search.sir"), """
                sir 0.2
                imports {
                  import Course from "modules/course.sir";
                  import Enrollment from "modules/enrollment.sir";
                  import EnrollmentStatus from "modules/enrollment.sir";
                  import SearchCourseEnrollmentsInput from "project.sir";
                  import CourseEnrollmentItem from "project.sir";
                  import InvalidPage from "project.sir";
                }
                declarations {
                """ + snippet + "\n}");
        var after = generate(source, temp.resolve("after"));
        var b = model(before.sources());
        var a = model(after.sources());
        var bd = b.declarations().stream().filter(d -> d.name().equals("SearchCourseEnrollments")).findFirst().orElseThrow();
        var ad = a.declarations().stream().filter(d -> d.name().equals("SearchCourseEnrollments")).findFirst().orElseThrow();
        assertEquals(bd.id(), ad.id()); assertNotEquals(bd.sourceNodeId(), ad.sourceNodeId());
        assertEquals(SourceId.of("modules/search.sir"), ad.span().source());
        // Each source unit was parsed anew; compare reference targets by source-local text and role, not old AST ids.
        var oldTargets = b.referenceSiteBindings().all().stream().filter(r -> r.site().span().source().equals(ENTRY)
                && r.site().span().start().codePointOffset() >= capability.span().start().codePointOffset()
                && r.site().span().end().codePointOffset() <= capability.span().end().codePointOffset())
                .filter(r -> b.symbols().byId(r.targetSymbol()).orElseThrow().kind() != io.kcg.sir.semantic.symbol.SymbolKind.VARIABLE)
                .map(r -> r.site().role() + ":" + r.site().text() + ":" + r.targetSymbol()).sorted().toList();
        var newTargets = a.referenceSiteBindings().all().stream().filter(r -> r.site().span().source().equals(SourceId.of("modules/search.sir")))
                .filter(r -> a.symbols().byId(r.targetSymbol()).orElseThrow().kind() != io.kcg.sir.semantic.symbol.SymbolKind.VARIABLE)
                .map(r -> r.site().role() + ":" + r.site().text() + ":" + r.targetSymbol()).sorted().toList();
        assertEquals(13, oldTargets.size()); assertEquals(oldTargets, newTargets);
        for (String local : List.of("courses", "item")) {
            var oldVariable = b.symbols().all().stream().filter(s -> s.kind() == io.kcg.sir.semantic.symbol.SymbolKind.VARIABLE && s.name().equals(local)
                    && s.declarationSpan().start().codePointOffset() >= capability.span().start().codePointOffset()).findFirst().orElseThrow();
            var newVariable = a.symbols().all().stream().filter(s -> s.kind() == io.kcg.sir.semantic.symbol.SymbolKind.VARIABLE && s.name().equals(local)
                    && s.declarationSpan().source().equals(SourceId.of("modules/search.sir"))).findFirst().orElseThrow();
            assertNotEquals(oldVariable.id(), newVariable.id(), "step-local identity is not a stable nodeKey");
            assertEquals(ENTRY, oldVariable.declarationSpan().source());
            assertEquals(SourceId.of("modules/search.sir"), newVariable.declarationSpan().source());
        }
        assertEquals(files(temp.resolve("before")), files(temp.resolve("after")));
        assertNotEquals(before.sources().sha256Hex(), after.sources().sha256Hex());
        assertNotEquals(before.graph().canonicalDigest(), after.graph().canonicalDigest());
    }

    @Test void existingOutputDoesNotMutateAnySourceOrOutputState() throws Exception {
        Path source = fixture(temp), output = temp.resolve("occupied"); Files.createDirectory(output); Files.writeString(output.resolve("user.txt"), "keep");
        var original = tree(temp);
        var result = new ToolchainApplication().executeProject(new ProjectToolchainRequest(source, ENTRY, output));
        var failure = assertInstanceOf(ProjectToolchainResult.Failure.class, result);
        assertEquals(FailureDisposition.NO_CHANGES, failure.disposition());
        assertEquals("SIR-APP-PROJECT-OUTPUT-001", failure.diagnostics().getFirst().code()); assertEquals(original, tree(temp));
    }
}

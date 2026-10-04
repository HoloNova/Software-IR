package io.kcg.sir.parser;

import static org.junit.jupiter.api.Assertions.*;
import io.kcg.sir.api.*;
import io.kcg.sir.ast.*;
import io.kcg.sir.source.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProjectSourceGrammarTest {
    static final String ROOT = """
        sir 0.2 software Demo {
          metadata { displayName "Demo"; namespace "example.demo"; }
          target { language java 21; framework spring_boot; persistence mybatis_plus; database mysql; build maven; interface rest; }
          sources { source "modules/course.sir"; }
          imports { import Course from "modules/course.sir"; }
          declarations { capability sources @id("query") { output Unit; expose query; workflow { return unit; } } }
        }
        """;
    private final SirParser parser = SirParser.create();
    private final SourceId id = SourceId.of("project.sir");

    @Test void parsesRealRootAndFragmentWithoutDuplicatedSoftware() {
        var result = parser.parseProject(new SirSource(id, ROOT));
        assertTrue(result.isSuccess(), result.diagnostics().toString());
        var root = assertInstanceOf(AstSourceUnit.Root.class, result.unit().orElseThrow());
        assertEquals("modules/course.sir", root.sources().getFirst().path());
        assertEquals("Course", root.imports().getFirst().declaration().text());
        assertEquals(id, root.imports().getFirst().from().span().source());
        var fragment = parser.parseFragment(new SirSource(SourceId.of("modules/course.sir"),
                "sir 0.2 imports {} declarations { entity Course persistent { identity id: Int64 generated auto; } }"));
        assertTrue(fragment.isSuccess(), fragment.diagnostics().toString());
        assertInstanceOf(AstSourceUnit.Fragment.class, fragment.unit().orElseThrow());
    }
    @Test void oldParserAndNewParserRemainVersionAndShapeIsolated() {
        assertFalse(parser.parse(new SirSource(id, ROOT)).isSuccess());
        assertFalse(parser.parseProject(new SirSource(id, ROOT.replace("0.2", "0.1"))).isSuccess());
        assertFalse(parser.parseFragment(new SirSource(id, ROOT)).isSuccess());
        assertFalse(parser.parseFragment(new SirSource(id, "sir 0.2 sources {} imports {} declarations {} ")).isSuccess());
        assertFalse(parser.parseFragment(new SirSource(id, "sir 0.2 imports {} declarations {} trailing")).isSuccess());
        assertFalse(parser.parseProject(new SirSource(id, ROOT.replace("sources {", "anything {"))).isSuccess());
        assertFalse(parser.parseProject(new SirSource(id, ROOT.replace("java 21", "java 17"))).isSuccess());
    }
    @Test void projectWordsStillParseAsLegacyIdentifiers() {
        for (String word : List.of("sources", "source", "imports", "import")) {
            String text = ROOT.replace("sir 0.2", "sir 0.1");
            text = text.substring(0, text.indexOf("  sources {")) + "declarations { entity " + word
                    + " persistent { identity id: Int64 generated auto; } } }";
            var result = parser.parse(new SirSource(id, text));
            assertTrue(result.isSuccess(), result.diagnostics().toString());
        }
    }
    @Test void lexicalErrorsAndExactFragmentLocationSurviveTheNewEntry() {
        var result = parser.parseFragment(new SirSource(SourceId.of("broken.sir"),
                "sir 0.2 imports { import Course from \"bad\\q\"; } declarations {}"));
        assertFalse(result.isSuccess());
        assertEquals(SourceId.of("broken.sir"), result.diagnostics().getFirst().primarySpan().source());
        assertTrue(result.diagnostics().getFirst().primarySpan().start().column() > 1);
    }
    @Test void snapshotFramingSortingAndCopiesBindEveryRawByte() {
        SourceId module = SourceId.of("modules/course.sir");
        byte[] rootBytes = ROOT.getBytes(StandardCharsets.UTF_8);
        byte[] part = "// raw bytes".getBytes(StandardCharsets.UTF_8);
        var snapshot = new SourceSnapshot(id, Map.of(id, rootBytes, module, part));
        var ordered = new LinkedHashMap<SourceId, byte[]>(); ordered.put(module, part); ordered.put(id, rootBytes);
        assertEquals(snapshot.sha256Hex(), new SourceSnapshot(id, ordered).sha256Hex());
        rootBytes[0] = 'x'; part[0] = 'x';
        assertEquals('s', snapshot.bytes(id)[0]);
        byte[] copy = snapshot.bytes(id); copy[0] = 'x';
        assertEquals('s', snapshot.bytes(id)[0]);
        assertEquals(snapshot.manifest(), SourceSetManifest.decode(snapshot.manifest().canonicalBytes()));
        assertNotEquals(snapshot.sha256Hex(), new SourceSnapshot(id, Map.of(id, rootBytes, module, part)).sha256Hex());
    }
    @Test void sourcePathsNeverHashLossyUnicodeOrExceedTheirUtf8FramingLimit() {
        assertThrows(IllegalArgumentException.class, () -> new SourceSnapshot(id, Map.of(id, new byte[0], SourceId.of("modules/\ud800.sir"), new byte[0])));
        assertThrows(IllegalArgumentException.class, () -> SourceSetManifest.strictId("modules/" + "a".repeat(512)));
        String exact = "a".repeat(508) + ".sir";
        assertEquals(exact, new SourceSetManifest.Entry(SourceId.of(exact), 0, "0".repeat(64)).sourceId().value());
        assertThrows(IllegalArgumentException.class, () -> new SourceSetManifest.Entry(SourceId.of(exact + "x"), 0, "0".repeat(64)));
    }

    @Test void damagedMissingDuplicateAndUnknownManifestDataAreRejected() {
        var entry = new SourceSetManifest.Entry(id, 0, "0".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> new SourceSetManifest(id, List.of(entry, entry)));
        assertThrows(IllegalArgumentException.class, () -> new SourceSetManifest(SourceId.of("missing.sir"), List.of(entry)));
        byte[] encoded = new SourceSetManifest(id, List.of(entry)).canonicalBytes();
        byte[] extra = Arrays.copyOf(encoded, encoded.length + 1);
        assertThrows(IllegalArgumentException.class, () -> SourceSetManifest.decode(extra));
        encoded[3] = 2;
        assertThrows(IllegalArgumentException.class, () -> SourceSetManifest.decode(encoded));
        assertThrows(IllegalArgumentException.class, () -> SourceSetManifest.strictId("modules\\course.sir"));
    }
}

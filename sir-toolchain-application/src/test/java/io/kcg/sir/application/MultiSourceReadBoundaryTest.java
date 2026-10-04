package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.*;
import static io.kcg.sir.application.MultiSourceTestSupport.*;
import io.kcg.sir.application.api.*;
import io.kcg.sir.application.internal.ProjectSourceReader;
import io.kcg.sir.source.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MultiSourceReadBoundaryTest {
    @TempDir Path temp;

    @Test void absoluteEscapingNoncanonicalAndNonportableSourceLiteralsAreRefusedWithoutAnyWrite() throws Exception {
        List<String> literals = List.of("/tmp/outside.sir", "../outside.sir", "C:/outside.sir", "modules/./course.sir",
                "modules//course.sir", "modules\\\\course.sir", "modules/foo:bar.sir", "modules/course.sir ", "modules/CON.sir");
        assertEquals(9, literals.size());
        for (int i = 0; i < literals.size(); i++) {
            Path directory = Files.createDirectory(temp.resolve("path-" + i)), source = fixture(directory), root = source.resolve("project.sir");
            Files.writeString(root, Files.readString(root).replace("source \"modules/course.sir\";", "source \"" + literals.get(i) + "\";"));
            refuse(directory, source, "SIR-APP-SOURCES-PATH-001");
        }
    }

    @Test void duplicateCaseCollidingAndHardLinkedLogicalPathsDoNotAliasOneDeclarationFile() throws Exception {
        for (String additional : List.of("modules/course.sir", "modules/Course.sir", "project.sir")) {
            Path directory = Files.createDirectory(temp.resolve("duplicate-" + additional.hashCode())), source = fixture(directory), root = source.resolve("project.sir");
            Files.writeString(root, Files.readString(root).replace("sources {", "sources { source \"" + additional + "\";"));
            refuse(directory, source, "SIR-APP-SOURCES-PATH-002");
        }
        Path directory = Files.createDirectory(temp.resolve("hardlink")), source = fixture(directory), root = source.resolve("project.sir");
        Files.createLink(source.resolve("modules/alias.sir"), source.resolve("modules/course.sir"));
        Files.writeString(root, Files.readString(root).replace("sources {", "sources { source \"modules/alias.sir\";"));
        refuse(directory, source, "SIR-APP-SOURCES-PATH-003");
    }

    @Test void sourceRootParentFragmentDirectoryAndFinalFileLinksAreNeverFollowed() throws Exception {
        // All cases operate on real Linux links, not string scans or unsupported-platform skips.
        Path fileCase = Files.createDirectory(temp.resolve("file-link")), source = fixture(fileCase);
        Path external = fileCase.resolve("external.sir"); Files.writeString(external, "outside source");
        Files.delete(source.resolve("modules/course.sir")); Files.createSymbolicLink(source.resolve("modules/course.sir"), external);
        refuse(fileCase, source, "SIR-APP-SOURCES-PATH-001");
        Path directoryCase = Files.createDirectory(temp.resolve("directory-link")); source = fixture(directoryCase);
        Path real = source.resolve("real-modules"); Files.move(source.resolve("modules"), real); Files.createSymbolicLink(source.resolve("modules"), real);
        refuse(directoryCase, source, "SIR-APP-SOURCES-READ-001");
        Path rootCase = Files.createDirectory(temp.resolve("root-link")); source = fixture(rootCase);
        Path alias = rootCase.resolve("alias"); Files.createSymbolicLink(alias, source);
        refuse(rootCase, alias, "SIR-APP-SOURCES-READ-001");
        Path parentCase = Files.createDirectory(temp.resolve("parent-link")); source = fixture(parentCase);
        Path parentAlias = temp.resolve("linked-parent"); Files.createSymbolicLink(parentAlias, parentCase);
        refuse(parentCase, parentAlias.resolve("sources"), "SIR-APP-SOURCES-READ-001");
    }

    @Test void missingDirectoryOccupiedAndInvalidUtf8SourcesFailBeforeGeneration() throws Exception {
        for (int i = 0; i < 3; i++) {
            Path directory = Files.createDirectory(temp.resolve("read-" + i)), source = fixture(directory), file = source.resolve("modules/course.sir");
            String code;
            if (i == 0) { Files.delete(file); code = "SIR-APP-SOURCES-READ-001"; }
            else if (i == 1) { Files.delete(file); Files.createDirectory(file); code = "SIR-APP-SOURCES-PATH-001"; }
            else { Files.write(file, new byte[] {(byte)0xc3, 0x28}); code = "SIR-APP-SOURCES-UTF8-001"; }
            refuse(directory, source, code);
        }
    }

    @Test void fileCountSingleFileAndTotalByteLimitsArePinnedAndRefusedBeforeOutput() throws Exception {
        assertEquals(128, SourceSetManifest.MAX_FILES); assertEquals(1024 * 1024, ProjectSourceReader.MAX_FILE_BYTES);
        assertEquals(8 * 1024 * 1024, ProjectSourceReader.MAX_TOTAL_BYTES);
        Path countCase = Files.createDirectory(temp.resolve("count")), source = fixture(countCase), root = source.resolve("project.sir");
        String header = header(root);
        StringBuilder sources = new StringBuilder("sources {");
        for (int i = 0; i < 128; i++) sources.append("source \"parts/").append(i).append(".sir\";");
        Files.writeString(root, header + sources + "} imports {} declarations {} }");
        refuse(countCase, source, "SIR-APP-SOURCES-LIMIT-001");
        Path sizeCase = Files.createDirectory(temp.resolve("single-size")); source = fixture(sizeCase);
        Files.write(source.resolve("modules/course.sir"), new byte[ProjectSourceReader.MAX_FILE_BYTES + 1]);
        refuse(sizeCase, source, "SIR-APP-SOURCES-LIMIT-001");
        Path totalCase = Files.createDirectory(temp.resolve("total-size")); source = fixture(totalCase); root = source.resolve("project.sir");
        sources = new StringBuilder("sources {");
        String fragment = "sir 0.2 imports {} declarations {}\n//";
        for (int i = 0; i < 8; i++) {
            sources.append("source \"modules/part").append(i).append(".sir\";");
            Files.writeString(source.resolve("modules/part" + i + ".sir"), fragment + "x".repeat(ProjectSourceReader.MAX_FILE_BYTES - fragment.length()));
        }
        Files.writeString(root, header(root) + sources + "} imports {} declarations {} }");
        refuse(totalCase, source, "SIR-APP-SOURCES-LIMIT-001");
    }

    @Test void exactSingleFileAndCountBoundariesAndRawBomCrLfAreAccepted() throws Exception {
        Path single = Files.createDirectory(temp.resolve("boundary-size")), source = fixture(single), root = source.resolve("project.sir");
        String text = header(root) + "sources {} imports {} declarations {} }\n//";
        text += "x".repeat(ProjectSourceReader.MAX_FILE_BYTES - text.getBytes(StandardCharsets.UTF_8).length);
        Files.writeString(root, text);
        var accepted = generate(source, single.resolve("output"));
        assertEquals(ProjectSourceReader.MAX_FILE_BYTES, accepted.sources().bytes(ENTRY).length);
        Path count = Files.createDirectory(temp.resolve("boundary-count")); source = fixture(count); root = source.resolve("project.sir");
        StringBuilder sources = new StringBuilder("sources {");
        for (int i = 0; i < 127; i++) {
            sources.append("source \"modules/part").append(i).append(".sir\";");
            Files.writeString(source.resolve("modules/part" + i + ".sir"), "sir 0.2 imports {} declarations {}");
        }
        Files.writeString(root, header(root) + sources + "} imports {} declarations {} }");
        assertEquals(128, generate(source, count.resolve("output")).sources().manifest().files().size());
        Path raw = Files.createDirectory(temp.resolve("raw-bytes")); source = fixture(raw); root = source.resolve("project.sir");
        Files.writeString(root, "\ufeff" + Files.readString(root).replace("\n", "\r\n"));
        var result = generate(source, raw.resolve("output")); assertArrayEquals(Files.readAllBytes(root), result.sources().bytes(ENTRY));
    }

    @Test void sourceOutputOverlapAndRelativeRequestsAreRefusedWithoutMutatingExistingState() throws Exception {
        Path source = fixture(temp); var before = tree(temp);
        for (var request : List.of(new ProjectToolchainRequest(source, ENTRY, source.resolve("generated")),
                new ProjectToolchainRequest(source, ENTRY, temp), new ProjectToolchainRequest(Path.of("relative"), ENTRY, temp.resolve("output")))) {
            var result = assertInstanceOf(ProjectToolchainResult.Failure.class, new ToolchainApplication().executeProject(request));
            assertEquals(ExecutionStage.READ, result.failedStage()); assertEquals(FailureDisposition.NO_CHANGES, result.disposition()); assertEquals(before, tree(temp));
        }
    }

    private static String header(Path root) throws Exception { String text = Files.readString(root); return text.substring(0, text.indexOf("  sources {")); }
    private static void refuse(Path directory, Path source, String expected) throws Exception {
        Path state = directory.resolve("state"); Files.createDirectories(state); Files.writeString(state.resolve("CURRENT"), "untouched authoritative sentinel");
        var before = tree(directory);
        var result = assertInstanceOf(ProjectToolchainResult.Failure.class, new ToolchainApplication().executeProject(new ProjectToolchainRequest(source, ENTRY, directory.resolve("output"))));
        assertEquals(ExecutionStage.READ, result.failedStage()); assertEquals(FailureDisposition.NO_CHANGES, result.disposition());
        assertEquals(List.of(expected), result.diagnostics().stream().filter(ExecutionDiagnostic::isError).map(ExecutionDiagnostic::code).toList());
        assertEquals(ENTRY, result.diagnostics().getFirst().sourceSpan().orElseThrow().source());
        assertEquals(before, tree(directory)); assertFalse(Files.exists(directory.resolve("output")));
    }
}

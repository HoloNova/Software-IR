package io.kcg.sir.projectgraph.boundary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.projectgraph.api.ProjectGraphAnalysis;
import io.kcg.sir.projectgraph.api.ProjectGraphLoader;
import io.kcg.sir.projectgraph.boundary.ClassFileConstantPoolReader.ClassFileFormatException;
import io.kcg.sir.projectgraph.boundary.ClassFileConstantPoolReader.ConstantPool;
import io.kcg.sir.projectgraph.boundary.ClassFileConstantPoolReader.EntryKind;
import io.kcg.sir.projectgraph.boundary.ClassFileConstantPoolReader.MemberReference;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Read-only boundary gate for the production Project Graph.
 *
 * <p>It proves, from the bytecode that actually enters the module's {@code target/classes}, that
 * {@code sir-project-graph} never reaches for the file system, the clock, a random source, the
 * process environment or a re-parse entry point, and that every {@code io.kcg.sir.*} type it
 * references is on an explicit review list. Source-level import checks cannot prove this: fully
 * qualified names, descriptors and non-imported references are invisible to them.
 *
 * <p>Two rule families are used, because a blanket prefix ban would be wrong here:
 *
 * <ul>
 *   <li><b>class-level forbidden prefixes</b>: any reference at all fails;
 *   <li><b>member review list</b> for {@code java/io/*} and {@code java/lang/System}: the production
 *       code legitimately uses {@code java.io.ByteArrayOutputStream} (an in-memory byte buffer) and
 *       {@code System.arraycopy} (an in-memory array copy). Those exact members are listed below
 *       with their justification, and <em>any other</em> member of those two owners fails — so
 *       {@code System.getenv} or {@code java.io.FileInputStream} would be reported by name.
 * </ul>
 *
 * <p>The gate is deliberately sensitive rather than merely green: {@link ForbiddenReferenceProbe} is
 * a test-only class that violates four of the rules on purpose, and both scans use the same rule
 * tables, so a rule that silently stopped matching fails the probe assertion instead of passing
 * quietly.
 */
class ProjectGraphReadOnlyBoundaryTest {

    /** Class-level rules: a single occurrence anywhere in the constant pool fails the gate. */
    private static final List<String> FORBIDDEN_REFERENCES = List.of(
            "java/nio/file/",
            "java/time/",
            "java/util/Random",
            "java/util/concurrent/ThreadLocalRandom",
            "java/security/SecureRandom",
            "java/util/UUID",
            "io/kcg/sir/api/",
            "io/kcg/sir/internal/",
            "io/kcg/sir/semantic/api/",
            "io/kcg/sir/semantic/internal/",
            "io/kcg/sir/semantic/context/",
            "io/kcg/sir/semantic/type/");

    /**
     * The only {@code java.io} / {@code java.lang.System} members reviewed and accepted as pure
     * in-memory operations. Everything else under those two owners is forbidden by name.
     */
    private static final Set<String> REVIEWED_MEMBERS = Set.of(
            "java/io/ByteArrayOutputStream.<init>",
            "java/io/ByteArrayOutputStream.write",
            "java/io/ByteArrayOutputStream.toByteArray",
            "java/lang/System.arraycopy");

    private static final Set<String> REVIEWED_MEMBER_OWNERS = Set.of("java/io/ByteArrayOutputStream",
            "java/lang/System");

    /** Own module: the graph classes themselves are always allowed. */
    private static final String OWN_MODULE_PREFIX = "io/kcg/sir/projectgraph/";

    /**
     * Review list for every other {@code io/kcg/sir/*} type the production classes may reference.
     * These are pure identity/model types: no analysis entry point, no symbol table, no normalized
     * model. Anything new fails the gate until it is reviewed and added here.
     */
    private static final List<String> ALLOWED_SIR_TYPES = List.of(
            "io/kcg/sir/ast/AstNodeId",
            "io/kcg/sir/lowering/api/LoweredNodeId",
            "io/kcg/sir/lowering/api/LoweredOrigin",
            "io/kcg/sir/semantic/symbol/SymbolId",
            "io/kcg/sir/semantic/symbol/SymbolKind",
            "io/kcg/sir/source/SourceId",
            "io/kcg/sir/source/SourcePosition",
            "io/kcg/sir/source/SourceSpan");

    private static final String PRODUCTION_PACKAGE_PREFIX = OWN_MODULE_PREFIX;
    private static final List<String> REQUIRED_CLASSES = List.of(
            PRODUCTION_PACKAGE_PREFIX + "api/ProjectGraphBuilder.class",
            PRODUCTION_PACKAGE_PREFIX + "api/ProjectGraphValidator.class",
            PRODUCTION_PACKAGE_PREFIX + "api/ProjectGraphLoader.class",
            PRODUCTION_PACKAGE_PREFIX + "api/ProjectGraphSerializer.class",
            PRODUCTION_PACKAGE_PREFIX + "internal/SnapshotDecoder.class",
            PRODUCTION_PACKAGE_PREFIX + "internal/SnapshotEncoder.class",
            PRODUCTION_PACKAGE_PREFIX + "internal/SnapshotLoader.class");
    private static final String PROBE_CLASS_MARKER = "ForbiddenReferenceProbe";

    /** The census observed when this gate was written: 30 production sources, 69 class files. */
    private static final int MINIMUM_PRODUCTION_CLASS_COUNT = 45;

    @Test
    void gateDetectsDeliberateForbiddenReferences() {
        Path probeClassFile = classFileOf(ForbiddenReferenceProbe.class);
        Path productionRoot = productionRoot();

        assertFalse(probeClassFile.startsWith(productionRoot),
                "the deliberate violation probe must stay outside production classes: " + probeClassFile);

        ConstantPool pool = ClassFileConstantPoolReader.read(probeClassFile);
        Set<String> detected = new LinkedHashSet<>();
        for (var entry : pool.utf8Entries()) {
            for (String rule : FORBIDDEN_REFERENCES) {
                if (entry.value().contains(rule)) {
                    detected.add(rule);
                }
            }
        }

        assertTrue(detected.contains("java/nio/file/"),
                () -> "gate did not detect java/nio/file/ in the probe, detected: " + detected);
        assertTrue(detected.contains("java/util/UUID"),
                () -> "gate did not detect java/util/UUID in the probe, detected: " + detected);
        assertTrue(detected.contains("io/kcg/sir/internal/"),
                () -> "gate did not detect the re-parse entry point in the probe, detected: " + detected);

        List<String> unreviewed = pool.memberReferences().stream()
                .map(MemberReference::member)
                .filter(this::isUnreviewedJdkMember)
                .distinct()
                .sorted()
                .toList();
        assertTrue(unreviewed.contains("java/lang/System.getenv"),
                () -> "gate did not detect System.getenv in the probe, detected: " + unreviewed);
    }

    @Test
    void productionScanPathReportsViolationsInACensus() {
        Path censusRoot = null;
        try {
            censusRoot = Files.createTempDirectory("projectgraph-boundary-census");
            copyInto(censusRoot, "boundary/ForbiddenReferenceProbe.class",
                    classFileOf(ForbiddenReferenceProbe.class));
            copyInto(censusRoot, "second/ForbiddenReferenceProbe.class",
                    classFileOf(ForbiddenReferenceProbe.class));

            List<Violation> violations = scan(classFiles(censusRoot));

            assertFalse(violations.isEmpty(),
                    "the production scan path must report violations when they exist");
            Set<String> rules = violations.stream().map(Violation::rule).collect(Collectors.toCollection(LinkedHashSet::new));
            assertTrue(rules.contains("java/nio/file/"), () -> "scan path lost java/nio/file/: " + rules);
            assertTrue(rules.contains("io/kcg/sir/internal/"),
                    () -> "scan path lost the re-parse entry point rule: " + rules);
            assertEquals(Set.of("boundary/ForbiddenReferenceProbe.class", "second/ForbiddenReferenceProbe.class"),
                    violations.stream().map(Violation::classFile).collect(Collectors.toSet()),
                    "both census entries must be reported");

            List<Violation> expectedOrder = new ArrayList<>(violations);
            expectedOrder.sort(Comparator.comparing(Violation::classFile)
                    .thenComparing(Violation::rule)
                    .thenComparingInt(Violation::constantPoolIndex));
            assertEquals(expectedOrder, violations, "violation reporting must be deterministic");

            String report = report(violations);
            assertTrue(report.contains("boundary/ForbiddenReferenceProbe.class [java/nio/file/]"), report);
            for (Violation violation : violations) {
                assertTrue(report.contains(violation.describe()),
                        () -> "report is missing a violation line: " + violation.describe());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            deleteRecursively(censusRoot);
        }
    }

    @Test
    void readerRejectsMalformedClassFiles() {
        Path synthetic = Path.of("synthetic-malformed.class");

        ClassFileFormatException badMagic = assertThrows(ClassFileFormatException.class,
                () -> ClassFileConstantPoolReader.read(synthetic, new byte[] {0x00, 0x01, 0x02, 0x03}));
        assertTrue(badMagic.getMessage().contains("synthetic-malformed.class"), badMagic.getMessage());
        assertTrue(badMagic.getMessage().contains("offset 0"), badMagic.getMessage());

        byte[] truncatedUtf8 = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE,
                0x00, 0x00, 0x00, 0x41, 0x00, 0x02, 0x01, 0x00, 0x05, 'a', 'b'};
        ClassFileFormatException truncation = assertThrows(ClassFileFormatException.class,
                () -> ClassFileConstantPoolReader.read(synthetic, truncatedUtf8));
        assertTrue(truncation.getMessage().contains("offset 13"), truncation.getMessage());
        assertTrue(truncation.getMessage().contains("truncated data"), truncation.getMessage());

        byte[] unknownTag = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE,
                0x00, 0x00, 0x00, 0x41, 0x00, 0x02, 99};
        ClassFileFormatException unknown = assertThrows(ClassFileFormatException.class,
                () -> ClassFileConstantPoolReader.read(synthetic, unknownTag));
        assertTrue(unknown.getMessage().contains("unknown constant pool tag 99"), unknown.getMessage());
        assertTrue(unknown.getMessage().contains("offset 10"), unknown.getMessage());

        byte[] missingHeader = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE,
                0x00, 0x00, 0x00, 0x41, 0x00, 0x01};
        ClassFileFormatException header = assertThrows(ClassFileFormatException.class,
                () -> ClassFileConstantPoolReader.read(synthetic, missingHeader));
        assertTrue(header.getMessage().contains("truncated after the constant pool"), header.getMessage());
    }

    @Test
    void productionClassesReferenceNoForbiddenCapability() {
        Path productionRoot = productionRoot();
        Map<String, Path> productionClasses = classFiles(productionRoot);

        assertTrue(productionClasses.size() >= MINIMUM_PRODUCTION_CLASS_COUNT,
                () -> "production census is too small to be the compiled Project Graph: "
                        + productionRoot + " -> " + productionClasses.size() + " classes");
        for (String required : REQUIRED_CLASSES) {
            assertTrue(productionClasses.containsKey(required),
                    () -> "production census is missing " + required);
        }
        assertTrue(productionClasses.keySet().stream().allMatch(name -> name.startsWith(PRODUCTION_PACKAGE_PREFIX)),
                () -> "production census contains classes outside " + PRODUCTION_PACKAGE_PREFIX);
        assertTrue(productionClasses.keySet().stream().noneMatch(name -> name.contains(PROBE_CLASS_MARKER)),
                () -> "the test-only violation probe leaked into the production census");

        List<Violation> violations = scan(productionClasses);
        assertTrue(violations.isEmpty(), () -> report(violations));
    }

    @Test
    void productionCensusOnlyReferencesAllowListedSirTypes() {
        Map<String, Path> productionClasses = classFiles(productionRoot());
        List<String> unreviewedSirTypes = new ArrayList<>();

        for (Map.Entry<String, Path> classFile : productionClasses.entrySet()) {
            ConstantPool pool = ClassFileConstantPoolReader.read(classFile.getValue());
            for (String referenced : pool.referencedClassNames()) {
                if (referenced.startsWith("io/kcg/sir/") && !isReviewedSirType(referenced)) {
                    unreviewedSirTypes.add(classFile.getKey() + " -> " + referenced);
                }
            }
        }

        unreviewedSirTypes.sort(Comparator.naturalOrder());
        assertTrue(unreviewedSirTypes.isEmpty(),
                () -> "production classes reference unreviewed io/kcg/sir types:\n  "
                        + String.join("\n  ", unreviewedSirTypes));
    }

    @Test
    void loaderSurfaceAcceptsOnlyInMemoryDocuments() {
        Class<ProjectGraphLoader> type = ProjectGraphLoader.class;

        assertTrue(Modifier.isPublic(type.getModifiers()), "ProjectGraphLoader must stay public");
        assertTrue(Modifier.isFinal(type.getModifiers()), "ProjectGraphLoader must stay final");

        List<Method> publicMethods = Arrays.stream(type.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> !method.isSynthetic() && !method.isBridge())
                .sorted(Comparator.comparing(Method::getName)
                        .thenComparing(method -> method.getParameterTypes()[0].getName()))
                .toList();

        assertEquals(List.of("load", "load"), publicMethods.stream().map(Method::getName).toList(),
                "ProjectGraphLoader must expose exactly two load overloads and nothing else");

        List<String> signatures = new ArrayList<>();
        for (Method method : publicMethods) {
            assertEquals(ProjectGraphAnalysis.class, method.getReturnType(),
                    "load must return ProjectGraphAnalysis");
            assertEquals(1, method.getParameterCount(), "load must take exactly one argument");

            List<String> parameterTypes = new ArrayList<>();
            for (Parameter parameter : method.getParameters()) {
                String name = parameter.getType().getName();
                parameterTypes.add(name);
                assertFalse(isForbiddenEntryType(name),
                        () -> "load must not accept external I/O types: " + name);
            }
            signatures.add(parameterTypes.toString());
        }

        assertEquals(List.of("[[B]", "[io.kcg.sir.projectgraph.api.CanonicalProjectGraphDocument]"),
                signatures, "load must accept raw bytes or an in-memory document only");

        Constructor<?>[] constructors = type.getDeclaredConstructors();
        assertEquals(1, constructors.length, "no extra production entry point is allowed");
        assertEquals(0, constructors[0].getParameterCount());
    }

    private static boolean isForbiddenEntryType(String typeName) {
        return typeName.startsWith("java.io.")
                || typeName.startsWith("java.nio.file.")
                || typeName.equals("java.io.File")
                || typeName.equals("java.io.InputStream")
                || typeName.equals("java.io.OutputStream")
                || typeName.equals("java.io.Reader")
                || typeName.equals("java.io.Writer");
    }

    private boolean isUnreviewedJdkMember(String member) {
        int dot = member.lastIndexOf('.');
        String owner = dot < 0 ? member : member.substring(0, dot);
        return REVIEWED_MEMBER_OWNERS.contains(owner) && !REVIEWED_MEMBERS.contains(member);
    }

    private static boolean isReviewedSirType(String internalName) {
        if (internalName.startsWith(OWN_MODULE_PREFIX)) {
            return true;
        }
        for (String allowed : ALLOWED_SIR_TYPES) {
            if (internalName.equals(allowed) || internalName.startsWith(allowed + "$")) {
                return true;
            }
        }
        return false;
    }

    private record Violation(String classFile, String rule, int constantPoolIndex, EntryKind kind, String text) {

        String describe() {
            return classFile + " [" + rule + "] constant pool #" + constantPoolIndex
                    + " (" + kind + "): " + text;
        }
    }

    private static List<Violation> scan(Map<String, Path> census) {
        List<Violation> violations = new ArrayList<>();

        for (Map.Entry<String, Path> classFile : census.entrySet()) {
            ConstantPool pool = ClassFileConstantPoolReader.read(classFile.getValue());

            for (var entry : pool.utf8Entries()) {
                for (String rule : FORBIDDEN_REFERENCES) {
                    if (entry.value().contains(rule)) {
                        violations.add(new Violation(classFile.getKey(), rule, entry.index(), entry.kind(),
                                entry.value()));
                    }
                }
            }

            for (MemberReference reference : pool.memberReferences()) {
                int dot = reference.member().lastIndexOf('.');
                String owner = dot < 0 ? reference.member() : reference.member().substring(0, dot);
                if (REVIEWED_MEMBER_OWNERS.contains(owner) && !REVIEWED_MEMBERS.contains(reference.member())) {
                    violations.add(new Violation(classFile.getKey(), "unreviewed-jdk-member",
                            reference.index(), EntryKind.OTHER, reference.member()));
                }
            }
        }

        violations.sort(Comparator.comparing(Violation::classFile)
                .thenComparing(Violation::rule)
                .thenComparingInt(Violation::constantPoolIndex));
        return List.copyOf(violations);
    }

    private static String report(List<Violation> violations) {
        StringBuilder report = new StringBuilder("production classes reference forbidden capabilities:\n");
        for (Violation violation : violations) {
            report.append("  ").append(violation.describe()).append('\n');
        }
        return report.toString();
    }

    private static Path productionRoot() {
        return codeSourceDirectory(io.kcg.sir.projectgraph.api.ProjectGraphLoader.class);
    }

    private static Path codeSourceDirectory(Class<?> anchor) {
        CodeSource codeSource = anchor.getProtectionDomain().getCodeSource();
        assertNotNull(codeSource, () -> "no code source for " + anchor.getName());

        URL location = codeSource.getLocation();
        assertNotNull(location, () -> "no code source location for " + anchor.getName());
        assertEquals("file", location.getProtocol(),
                () -> "expected an exploded class directory for " + anchor.getName() + ", got " + location);

        Path root = toPath(location);
        assertTrue(Files.isDirectory(root), () -> "code source is not a directory: " + root);
        return root;
    }

    private static Path classFileOf(Class<?> type) {
        URL url = type.getResource("/" + type.getName().replace('.', '/') + ".class");
        assertNotNull(url, () -> "cannot locate the class file of " + type.getName());
        assertEquals("file", url.getProtocol(),
                () -> "expected an exploded class file for " + type.getName() + ", got " + url);

        Path classFile = toPath(url);
        assertTrue(Files.isRegularFile(classFile), () -> "class file is missing: " + classFile);
        return classFile;
    }

    private static Path toPath(URL url) {
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("not a file URL: " + url, e);
        }
    }

    private static void copyInto(Path root, String relativeName, Path source) throws IOException {
        Path target = root.resolve(relativeName);
        Files.createDirectories(target.getParent());
        Files.copy(source, target);
    }

    private static void deleteRecursively(Path root) {
        if (root == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, Path> classFiles(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".class"))
                    .collect(Collectors.toMap(path -> relativeName(root, path), path -> path,
                            (first, second) -> first, TreeMap::new));
        } catch (IOException e) {
            throw new IllegalStateException("cannot walk production classes: " + root, e);
        }
    }

    private static String relativeName(Path root, Path file) {
        List<String> segments = new ArrayList<>();
        root.relativize(file).forEach(segment -> segments.add(segment.toString()));
        return String.join("/", segments);
    }
}

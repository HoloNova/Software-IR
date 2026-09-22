package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.generator.springboot.api.GenerationResult;
import io.kcg.sir.generator.springboot.api.SpringBootGenerator;
import io.kcg.sir.generator.springboot.boundary.ClassFileConstantPoolReader;
import io.kcg.sir.generator.springboot.boundary.ClassFileConstantPoolReader.ClassFileFormatException;
import io.kcg.sir.generator.springboot.boundary.ClassFileConstantPoolReader.ConstantPool;
import io.kcg.sir.generator.springboot.boundary.ClassFileConstantPoolReader.EntryKind;
import io.kcg.sir.generator.springboot.boundary.ClassFileConstantPoolReader.Utf8Entry;
import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.UncheckedIOException;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Architecture gate for the production Generator boundary.
 *
 * <p>It proves, from the bytecode that actually enters the module's {@code target/classes}, that
 * {@code sir-generator-spring-boot} consumes only {@code SpringBootLoweredModel} and never reaches
 * for a Parser/AST/Semantic input, the file system, the process environment, the clock or a random
 * source. Source-level import checks cannot prove this: fully qualified names, descriptors and
 * non-imported references are invisible to them, and they would keep passing after the module
 * changed its layout.
 *
 * <p>The gate is deliberately sensitive rather than merely green. {@link ForbiddenReferenceProbe}
 * is a test-only class that violates the boundary on purpose; the gate first has to detect it, and
 * only then scans the production class census. Both scans use the same rule table, so a rule that
 * silently stopped matching would fail the probe assertion instead of passing quietly.
 */
class GeneratorProductionBoundaryTest {

    /**
     * Forbidden references, matched as substrings of every constant pool {@code CONSTANT_Utf8}
     * entry. Substring matching covers internal class names, field/method descriptors, member names
     * and string literals in one pass.
     */
    private static final List<String> FORBIDDEN_REFERENCES = List.of(
            "io/kcg/sir/ast/",
            "io/kcg/sir/api/",
            "io/kcg/sir/internal/",
            "io/kcg/sir/source/",
            "io/kcg/sir/semantic/api/",
            "io/kcg/sir/semantic/model/",
            "io/kcg/sir/semantic/symbol/",
            "java/nio/file/",
            "java/io/",
            "java/lang/System",
            "java/time/",
            "java/util/Random",
            "java/util/concurrent/ThreadLocalRandom",
            "java/security/SecureRandom",
            "java/util/UUID");

    /**
     * The single Semantic type the frozen generated-file contract is allowed to carry as ownership
     * metadata. Every other type in this package stays forbidden.
     */
    private static final String SYMBOL_PACKAGE = "io/kcg/sir/semantic/symbol/";
    private static final String ALLOWED_SYMBOL_TYPE = "SymbolId";

    /** The scanner must reject these, so a wrong or empty code source cannot produce a false pass. */
    private static final String PRODUCTION_PACKAGE_PREFIX = "io/kcg/sir/generator/springboot/";
    private static final String GENERATOR_ENTRY_CLASS = PRODUCTION_PACKAGE_PREFIX + "api/SpringBootGenerator.class";
    private static final String GENERATION_ENGINE_CLASS = PRODUCTION_PACKAGE_PREFIX + "internal/GenerationEngine.class";
    private static final String INTERNAL_RENDERER_CLASS = PRODUCTION_PACKAGE_PREFIX + "internal/EntityRenderer.class";
    private static final String PROBE_CLASS_MARKER = "ForbiddenReferenceProbe";

    /**
     * Lower bound, not an exact count: the census observed when this gate was written held 37 class
     * files compiled from 22 production sources. Growing the module is allowed, silently scanning an
     * empty or unrelated directory is not.
     */
    private static final int MINIMUM_PRODUCTION_CLASS_COUNT = 30;

    /**
     * Type prefixes the public generation entry may never mention. The bytecode scan already covers
     * every reference; this reflection check pins the entry signature itself.
     */
    private static final List<String> FORBIDDEN_ENTRY_TYPE_PREFIXES = List.of(
            "io.kcg.sir.ast.",
            "io.kcg.sir.api.",
            "io.kcg.sir.internal.",
            "io.kcg.sir.source.",
            "io.kcg.sir.semantic.",
            "java.nio.file.",
            "java.io.");

    @Test
    void gateDetectsDeliberateForbiddenReferences() {
        Path probeClassFile = classFileOf(ForbiddenReferenceProbe.class);
        Path productionRoot = productionRoot();

        assertFalse(probeClassFile.startsWith(productionRoot),
                "the deliberate violation probe must stay outside production classes: " + probeClassFile);

        ConstantPool pool = ClassFileConstantPoolReader.read(probeClassFile);
        List<String> detected = new ArrayList<>();
        for (Utf8Entry entry : pool.utf8Entries()) {
            for (String rule : FORBIDDEN_REFERENCES) {
                if (matches(rule, entry.value())) {
                    detected.add(rule);
                }
            }
        }

        assertTrue(detected.contains("java/nio/file/"),
                () -> "gate did not detect java/nio/file/ in the probe, detected: " + detected);
        assertTrue(detected.contains("java/lang/System"),
                () -> "gate did not detect java/lang/System in the probe, detected: " + detected);
        assertTrue(detected.contains("java/io/"),
                () -> "gate did not detect java/io/ in the probe, detected: " + detected);

        // The reported entry kind is what separates a real type reference from a string literal
        // that merely mentions a forbidden name, so the classification itself is asserted here.
        List<String> referencedClasses = pool.entriesOfKind(EntryKind.CLASS_NAME).stream()
                .map(Utf8Entry::value)
                .toList();
        assertTrue(referencedClasses.contains("java/nio/file/Files"),
                () -> "probe class references were not classified as class names: " + referencedClasses);
        assertTrue(referencedClasses.contains("java/lang/System"),
                () -> "probe class references were not classified as class names: " + referencedClasses);
        assertTrue(referencedClasses.contains("java/io/IOException"),
                () -> "probe class references were not classified as class names: " + referencedClasses);
    }

    /**
     * Drives the production scan path itself ({@code classFiles} + {@code scan} + {@code report})
     * over a census of real, deliberately violating class files. Without this, a defect that made
     * {@code scan} always return an empty list would look exactly like a clean production module.
     *
     * <p>The two fixtures are the test-only probe and an existing test support class, which proves
     * both halves of the boundary: the JDK rules ({@code java/nio/file/}, {@code java/lang/System})
     * and the compiler-input rules ({@code io/kcg/sir/api/}, {@code io/kcg/sir/internal/}) are live,
     * and test classes carrying them would be reported if they ever leaked into production output.
     */
    @Test
    void productionScanPathReportsViolationsInACensus() {
        Path censusRoot = null;
        try {
            censusRoot = Files.createTempDirectory("generator-boundary-census");
            copyInto(censusRoot, "boundary/ForbiddenReferenceProbe.class",
                    classFileOf(ForbiddenReferenceProbe.class));
            copyInto(censusRoot, "support/GeneratorTestSupport.class",
                    classFileOf(GeneratorTestSupport.class));

            List<Violation> violations = scan(readConstantPools(classFiles(censusRoot)));

            List<String> rules = violations.stream().map(Violation::rule).distinct().toList();
            assertTrue(rules.contains("java/nio/file/"), () -> "scan path lost java/nio/file/: " + rules);
            assertTrue(rules.contains("java/lang/System"), () -> "scan path lost java/lang/System: " + rules);
            assertTrue(rules.contains("io/kcg/sir/api/"), () -> "scan path lost io/kcg/sir/api/: " + rules);
            assertTrue(rules.contains("io/kcg/sir/internal/"),
                    () -> "scan path lost io/kcg/sir/internal/: " + rules);

            List<String> scannedClasses = violations.stream().map(Violation::classFile).distinct().toList();
            assertTrue(scannedClasses.contains("boundary/ForbiddenReferenceProbe.class"),
                    () -> "scan path lost the probe class: " + scannedClasses);
            assertTrue(scannedClasses.contains("support/GeneratorTestSupport.class"),
                    () -> "scan path lost the test support class: " + scannedClasses);

            List<Violation> expectedOrder = new ArrayList<>(violations);
            expectedOrder.sort(Comparator.comparing(Violation::classFile)
                    .thenComparing(Violation::rule)
                    .thenComparingInt(Violation::constantPoolIndex));
            assertEquals(expectedOrder, violations, "violation reporting order must be deterministic");

            String report = report(violations);
            for (Violation violation : violations) {
                assertTrue(report.contains(violation.describe()),
                        () -> "report is missing a violation line: " + violation.describe());
            }
            assertTrue(report.contains("boundary/ForbiddenReferenceProbe.class ["), report);
            assertTrue(report.contains("support/GeneratorTestSupport.class ["), report);

            assertFalse(censusRoot.startsWith(productionRoot()),
                    "the violation census must stay outside production classes: " + censusRoot);
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
        assertTrue(badMagic.getMessage().contains("invalid magic number"), badMagic.getMessage());

        byte[] truncatedUtf8 = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE,
                0x00, 0x00, 0x00, 0x41, 0x00, 0x02, 0x01, 0x00, 0x05, 'a', 'b'};
        ClassFileFormatException truncation = assertThrows(ClassFileFormatException.class,
                () -> ClassFileConstantPoolReader.read(synthetic, truncatedUtf8));
        assertTrue(truncation.getMessage().contains("synthetic-malformed.class"), truncation.getMessage());
        assertTrue(truncation.getMessage().contains("offset 13"), truncation.getMessage());
        assertTrue(truncation.getMessage().contains("truncated data"), truncation.getMessage());

        byte[] unknownTag = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE,
                0x00, 0x00, 0x00, 0x41, 0x00, 0x02, 99};
        ClassFileFormatException unknown = assertThrows(ClassFileFormatException.class,
                () -> ClassFileConstantPoolReader.read(synthetic, unknownTag));
        assertTrue(unknown.getMessage().contains("offset 10"), unknown.getMessage());
        assertTrue(unknown.getMessage().contains("unknown constant pool tag 99"), unknown.getMessage());

        byte[] missingHeader = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE,
                0x00, 0x00, 0x00, 0x41, 0x00, 0x01};
        ClassFileFormatException header = assertThrows(ClassFileFormatException.class,
                () -> ClassFileConstantPoolReader.read(synthetic, missingHeader));
        assertTrue(header.getMessage().contains("offset 10"), header.getMessage());
        assertTrue(header.getMessage().contains("truncated after the constant pool"), header.getMessage());
    }

    @Test
    void productionClassesReferenceNoForbiddenDependency() {
        Path productionRoot = productionRoot();
        Map<String, Path> productionClasses = classFiles(productionRoot);

        assertTrue(productionClasses.size() >= MINIMUM_PRODUCTION_CLASS_COUNT,
                () -> "production census is too small to be the compiled Generator: "
                        + productionRoot + " -> " + productionClasses.keySet());
        assertTrue(productionClasses.containsKey(GENERATOR_ENTRY_CLASS),
                () -> "production census is missing " + GENERATOR_ENTRY_CLASS + ": " + productionClasses.keySet());
        assertTrue(productionClasses.containsKey(GENERATION_ENGINE_CLASS),
                () -> "production census is missing " + GENERATION_ENGINE_CLASS + ": " + productionClasses.keySet());
        assertTrue(productionClasses.containsKey(INTERNAL_RENDERER_CLASS),
                () -> "production census is missing " + INTERNAL_RENDERER_CLASS + ": " + productionClasses.keySet());
        assertTrue(productionClasses.keySet().stream().allMatch(name -> name.startsWith(PRODUCTION_PACKAGE_PREFIX)),
                () -> "production census contains classes outside " + PRODUCTION_PACKAGE_PREFIX
                        + ": " + productionClasses.keySet());
        assertTrue(productionClasses.keySet().stream().noneMatch(name -> name.contains(PROBE_CLASS_MARKER)),
                () -> "the test-only violation probe leaked into the production census: "
                        + productionClasses.keySet());

        Map<String, ConstantPool> constantPools = readConstantPools(productionClasses);

        List<Violation> violations = scan(constantPools);
        assertTrue(violations.isEmpty(), () -> report(violations));

        // The Semantic package rule must really engage instead of being vacuously green: the frozen
        // generated-file ownership metadata is present in the production classes and passes only
        // through the documented exact-SymbolId exception.
        assertTrue(referencesClassName(constantPools, SYMBOL_PACKAGE + ALLOWED_SYMBOL_TYPE),
                "the production census must carry the allowed "
                        + (SYMBOL_PACKAGE + ALLOWED_SYMBOL_TYPE) + " ownership metadata reference");
    }

    @Test
    void publicGenerationEntryOnlyConsumesLoweredModel() {
        Class<SpringBootGenerator> type = SpringBootGenerator.class;

        assertTrue(Modifier.isPublic(type.getModifiers()), "SpringBootGenerator must stay public");
        assertTrue(Modifier.isFinal(type.getModifiers()), "SpringBootGenerator must stay final");

        List<Method> publicMethods = Arrays.stream(type.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> !method.isSynthetic() && !method.isBridge())
                .sorted(Comparator.comparing(Method::getName))
                .toList();

        assertEquals(List.of("generate"), publicMethods.stream().map(Method::getName).toList(),
                "SpringBootGenerator must expose exactly one public generation method");

        Method generate = publicMethods.get(0);
        assertEquals(GenerationResult.class, generate.getReturnType(),
                "generate must return GenerationResult");
        assertArrayEquals(new Class<?>[] {SpringBootLoweredModel.class}, generate.getParameterTypes(),
                "generate must accept SpringBootLoweredModel and nothing else");

        Constructor<?>[] constructors = type.getDeclaredConstructors();
        assertEquals(1, constructors.length,
                "no extra production entry point or test constructor is allowed");
        assertEquals(0, constructors[0].getParameterCount(),
                "the only production constructor must stay argument free");
    }

    @Test
    void generationEntrySurfaceExposesNoCompilerInternals() {
        List<String> exposed = new ArrayList<>();

        for (Method method : SpringBootGenerator.class.getDeclaredMethods()) {
            collectExposedType(exposed, "method " + method.getName() + " return type", method.getReturnType());
            for (Parameter parameter : method.getParameters()) {
                collectExposedType(exposed,
                        "method " + method.getName() + " parameter " + parameter.getName(), parameter.getType());
            }
        }
        for (Constructor<?> constructor : SpringBootGenerator.class.getDeclaredConstructors()) {
            for (Parameter parameter : constructor.getParameters()) {
                collectExposedType(exposed,
                        "constructor parameter " + parameter.getName(), parameter.getType());
            }
        }

        assertTrue(exposed.isEmpty(),
                () -> "SpringBootGenerator signature exposes forbidden input/output types: " + exposed);
    }

    private record Violation(String classFile, String rule, int constantPoolIndex, EntryKind kind, String text) {

        String describe() {
            return classFile + " [" + rule + "] constant pool #" + constantPoolIndex
                    + " (" + kind + "): " + text;
        }
    }

    private static Map<String, ConstantPool> readConstantPools(Map<String, Path> census) {
        Map<String, ConstantPool> pools = new TreeMap<>();
        for (Map.Entry<String, Path> classFile : census.entrySet()) {
            pools.put(classFile.getKey(), ClassFileConstantPoolReader.read(classFile.getValue()));
        }
        return pools;
    }

    private static boolean referencesClassName(Map<String, ConstantPool> constantPools, String internalName) {
        for (ConstantPool pool : constantPools.values()) {
            for (Utf8Entry entry : pool.entriesOfKind(EntryKind.CLASS_NAME)) {
                if (entry.value().equals(internalName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<Violation> scan(Map<String, ConstantPool> constantPools) {
        List<Violation> violations = new ArrayList<>();

        for (Map.Entry<String, ConstantPool> classFile : constantPools.entrySet()) {
            for (Utf8Entry entry : classFile.getValue().utf8Entries()) {
                for (String rule : FORBIDDEN_REFERENCES) {
                    if (matches(rule, entry.value())) {
                        violations.add(new Violation(classFile.getKey(), rule, entry.index(), entry.kind(),
                                entry.value()));
                    }
                }
            }
        }

        violations.sort(Comparator.comparing(Violation::classFile)
                .thenComparing(Violation::rule)
                .thenComparingInt(Violation::constantPoolIndex));
        return List.copyOf(violations);
    }

    private static String report(List<Violation> violations) {
        StringBuilder report = new StringBuilder("production classes reference forbidden dependencies:\n");
        for (Violation violation : violations) {
            report.append("  ").append(violation.describe()).append('\n');
        }
        return report.toString();
    }

    private static boolean matches(String rule, String text) {
        if (!text.contains(rule)) {
            return false;
        }
        return !SYMBOL_PACKAGE.equals(rule) || !onlyReferencesSymbolId(text);
    }

    /**
     * {@code SymbolId} is the one allowed Semantic type, and it is allowed as a whole type only:
     * {@code io/kcg/sir/semantic/symbol/SymbolId} as an internal class name and
     * {@code Lio/kcg/sir/semantic/symbol/SymbolId;} as a descriptor. Any other type in that package,
     * including {@code SymbolTable} or a nested {@code SymbolId$...}, stays forbidden.
     */
    private static boolean onlyReferencesSymbolId(String text) {
        int from = 0;
        while (true) {
            int at = text.indexOf(SYMBOL_PACKAGE, from);
            if (at < 0) {
                return true;
            }

            String remainder = text.substring(at + SYMBOL_PACKAGE.length());
            if (!remainder.startsWith(ALLOWED_SYMBOL_TYPE)) {
                return false;
            }

            String afterType = remainder.substring(ALLOWED_SYMBOL_TYPE.length());
            if (!afterType.isEmpty() && !afterType.startsWith(";")) {
                return false;
            }

            from = at + SYMBOL_PACKAGE.length();
        }
    }

    private static void collectExposedType(List<String> exposed, String usage, Class<?> type) {
        Class<?> resolved = type;
        while (resolved.isArray()) {
            resolved = resolved.getComponentType();
        }

        String name = resolved.getName();
        for (String prefix : FORBIDDEN_ENTRY_TYPE_PREFIXES) {
            if (name.startsWith(prefix)) {
                exposed.add(usage + " -> " + name);
                return;
            }
        }
    }

    private static Path productionRoot() {
        return codeSourceDirectory(SpringBootGenerator.class);
    }

    private static Path codeSourceDirectory(Class<?> anchor) {
        CodeSource codeSource = anchor.getProtectionDomain().getCodeSource();
        assertNotNull(codeSource, () -> "no code source for " + anchor.getName());

        URL location = codeSource.getLocation();
        assertNotNull(location, () -> "no code source location for " + anchor.getName());
        assertEquals("file", location.getProtocol(),
                () -> "expected an exploded class directory for " + anchor.getName() + ", got " + location);

        Path root = toPath(location);
        assertTrue(Files.isDirectory(root),
                () -> "code source of " + anchor.getName() + " is not a directory: " + root);
        return root;
    }

    private static Path classFileOf(Class<?> type) {
        URL url = type.getResource("/" + type.getName().replace('.', '/') + ".class");
        assertNotNull(url, () -> "cannot locate the class file of " + type.getName());
        assertEquals("file", url.getProtocol(),
                () -> "expected an exploded class file for " + type.getName() + ", got " + url);

        Path classFile = toPath(url);
        assertTrue(Files.isRegularFile(classFile),
                () -> "class file of " + type.getName() + " is missing: " + classFile);
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

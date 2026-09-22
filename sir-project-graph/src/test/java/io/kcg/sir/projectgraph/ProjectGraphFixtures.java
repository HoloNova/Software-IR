package io.kcg.sir.projectgraph;

import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.lowering.api.LoweredOrigin;
import io.kcg.sir.projectgraph.api.ArtifactRole;
import io.kcg.sir.projectgraph.api.GraphEdgeKind;
import io.kcg.sir.projectgraph.api.GraphNodeId;
import io.kcg.sir.projectgraph.api.GraphVersion;
import io.kcg.sir.projectgraph.api.ProjectGraphInput;
import io.kcg.sir.projectgraph.api.ProjectGraphInput.ArtifactInput;
import io.kcg.sir.projectgraph.api.ProjectGraphInput.EdgeBinding;
import io.kcg.sir.projectgraph.api.ProjectGraphInput.FileInput;
import io.kcg.sir.projectgraph.api.ProjectGraphInput.LoweredDeclarationInput;
import io.kcg.sir.projectgraph.api.ProjectGraphInput.SemanticDeclarationInput;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.semantic.symbol.SymbolKind;
import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourcePosition;
import io.kcg.sir.source.SourceSpan;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Hand-built {@link ProjectGraphInput} fixtures for the direct Project Graph contract tests.
 *
 * <p>The default fixture reproduces the campus-market graph shape already recorded by
 * {@code sir-toolchain-application}'s {@code ToolchainProjectGraphIntegrationTest}: 35 nodes
 * (1 Project + 6 Semantic + 6 Lowered + 11 Artifact + 11 ProjectFile) and 34 edges
 * (6 DECLARES + 6 LOWERS_TO + 11 OWNS_ARTIFACT + 11 GENERATES_FILE). The composition is the same;
 * the fixture is built in-module so {@code sir-project-graph} needs no dependency on the
 * Application, Generator or Spring Boot Lowering modules.
 *
 * <p>Everything here is test-only and deliberately explicit: the fixtures exist to be perturbed one
 * rule at a time, so each helper changes exactly one thing and leaves the rest valid.
 */
final class ProjectGraphFixtures {

    static final SourceId SOURCE_ID = SourceId.of("campus-market.sir");
    static final String SOFTWARE_NAME = "CampusMarket";
    static final String BASE_PACKAGE = "com.example.campusmarket";
    static final String PROJECT_DISPLAY_NAME = "Campus Market";

    static final SymbolId ENUM_SYMBOL = symbol("enum", "GoodsStatus");
    static final SymbolId USER_ENTITY_SYMBOL = symbol("entity", "User");
    static final SymbolId GOODS_ENTITY_SYMBOL = symbol("entity", "Goods");
    static final SymbolId INPUT_SYMBOL = symbol("input", "PublishGoodsInput");
    static final SymbolId ERROR_SYMBOL = symbol("error", "InvalidGoodsPrice");
    static final SymbolId CAPABILITY_SYMBOL = symbol("capability", "PublishGoods");

    static final GraphNodeId.ProjectNodeId PROJECT_ID = GraphNodeId.ProjectNodeId.INSTANCE;

    /** One top-level semantic declaration and the artifacts it owns. */
    private record Declaration(SymbolKind kind, SymbolId symbol, String displayName, List<ArtifactSpec> artifacts) {
    }

    /** One generated artifact: its role plus the package and class name it renders into. */
    private record ArtifactSpec(ArtifactRole.DeclarationRole role, String subpackage, String javaName) {
    }

    private static ArtifactSpec spec(ArtifactRole.DeclarationRole role, String subpackage, String javaName) {
        return new ArtifactSpec(role, subpackage, javaName);
    }

    private static final List<Declaration> DECLARATIONS = List.of(
            new Declaration(SymbolKind.ENUM, ENUM_SYMBOL, "GoodsStatus",
                    List.of(spec(ArtifactRole.DeclarationRole.ENUM, "enums", "GoodsStatus"))),
            new Declaration(SymbolKind.ENTITY, USER_ENTITY_SYMBOL, "User",
                    List.of(spec(ArtifactRole.DeclarationRole.ENTITY_MODEL, "entity", "User"),
                            spec(ArtifactRole.DeclarationRole.MAPPER, "mapper", "UserMapper"))),
            new Declaration(SymbolKind.ENTITY, GOODS_ENTITY_SYMBOL, "Goods",
                    List.of(spec(ArtifactRole.DeclarationRole.ENTITY_MODEL, "entity", "Goods"),
                            spec(ArtifactRole.DeclarationRole.MAPPER, "mapper", "GoodsMapper"))),
            new Declaration(SymbolKind.INPUT, INPUT_SYMBOL, "PublishGoodsInput",
                    List.of(spec(ArtifactRole.DeclarationRole.REQUEST_DTO, "dto", "PublishGoodsInput"))),
            new Declaration(SymbolKind.ERROR, ERROR_SYMBOL, "InvalidGoodsPriceException",
                    List.of(spec(ArtifactRole.DeclarationRole.EXCEPTION, "exception", "InvalidGoodsPriceException"))),
            new Declaration(SymbolKind.CAPABILITY, CAPABILITY_SYMBOL, "PublishGoods",
                    List.of(spec(ArtifactRole.DeclarationRole.SERVICE, "service", "PublishGoodsService"),
                            spec(ArtifactRole.DeclarationRole.CONTROLLER, "controller", "PublishGoodsController"))));

    private ProjectGraphFixtures() {
    }

    /** The valid campus-market-shaped input: 35 nodes, 34 edges. */
    static ProjectGraphInput campusMarket() {
        List<SemanticDeclarationInput> semantics = new ArrayList<>();
        List<LoweredDeclarationInput> lowered = new ArrayList<>();
        List<ArtifactInput> artifacts = new ArrayList<>();
        List<FileInput> files = new ArrayList<>();
        List<EdgeBinding> edges = new ArrayList<>();

        int offset = 0;
        for (Declaration declaration : DECLARATIONS) {
            SourceSpan span = span(offset);
            AstNodeId sourceNodeId = astId(declaration.symbol());
            offset += 10;

            semantics.add(new SemanticDeclarationInput(declaration.symbol(), declaration.kind(),
                    declaration.displayName(), sourceNodeId, span));
            edges.add(new EdgeBinding(GraphEdgeKind.DECLARES, PROJECT_ID,
                    new GraphNodeId.Semantic(declaration.symbol())));

            LoweredNodeId loweredId = loweredId(declaration.symbol());
            lowered.add(new LoweredDeclarationInput(loweredId, declaration.symbol(),
                    declaration.displayName(), origin(declaration.symbol(), sourceNodeId, span)));
            edges.add(new EdgeBinding(GraphEdgeKind.LOWERS_TO,
                    new GraphNodeId.Semantic(declaration.symbol()), new GraphNodeId.Lowered(loweredId)));

            for (ArtifactSpec artifact : declaration.artifacts()) {
                String qualifiedName = qualifiedName(artifact.subpackage(), artifact.javaName());
                String path = relativePath(artifact.subpackage(), artifact.javaName());
                LoweredNodeId artifactId = artifactId(declaration.symbol(), artifact.role());

                artifacts.add(new ArtifactInput(artifactId, Optional.of(declaration.symbol()),
                        origin(declaration.symbol(), sourceNodeId, span), artifact.role(), qualifiedName));
                edges.add(new EdgeBinding(GraphEdgeKind.OWNS_ARTIFACT, new GraphNodeId.Lowered(loweredId),
                        new GraphNodeId.Lowered(artifactId)));
                files.add(fileInput(path, artifactId, Optional.of(declaration.symbol())));
                edges.add(new EdgeBinding(GraphEdgeKind.GENERATES_FILE, new GraphNodeId.Lowered(artifactId),
                        new GraphNodeId.File(path)));
            }
        }

        LoweredNodeId mavenArtifactId = new LoweredNodeId("sir://" + SOFTWARE_NAME + "/artifact/pom");
        artifacts.add(new ArtifactInput(mavenArtifactId, Optional.empty(),
                projectOrigin("ast://" + SOFTWARE_NAME + "/artifact/pom"), ArtifactRole.ProjectRole.MAVEN_PROJECT,
                "campus-market"));
        edges.add(new EdgeBinding(GraphEdgeKind.OWNS_ARTIFACT, PROJECT_ID, new GraphNodeId.Lowered(mavenArtifactId)));
        files.add(fileInput("pom.xml", mavenArtifactId, Optional.empty()));
        edges.add(new EdgeBinding(GraphEdgeKind.GENERATES_FILE, new GraphNodeId.Lowered(mavenArtifactId),
                new GraphNodeId.File("pom.xml")));

        String applicationPath = relativePath("", "Application");
        LoweredNodeId applicationArtifactId = new LoweredNodeId("sir://" + SOFTWARE_NAME + "/artifact/application");
        artifacts.add(new ArtifactInput(applicationArtifactId, Optional.empty(),
                projectOrigin("ast://" + SOFTWARE_NAME + "/artifact/application"),
                ArtifactRole.ProjectRole.APPLICATION_MAIN, qualifiedName("", "Application")));
        edges.add(new EdgeBinding(GraphEdgeKind.OWNS_ARTIFACT, PROJECT_ID,
                new GraphNodeId.Lowered(applicationArtifactId)));
        files.add(fileInput(applicationPath, applicationArtifactId, Optional.empty()));
        edges.add(new EdgeBinding(GraphEdgeKind.GENERATES_FILE, new GraphNodeId.Lowered(applicationArtifactId),
                new GraphNodeId.File(applicationPath)));

        return new ProjectGraphInput(GraphVersion.V0_1, SOURCE_ID, PROJECT_DISPLAY_NAME, semantics, lowered,
                artifacts, files, edges);
    }

    // ---- perturbation helpers: each one changes exactly one aspect ----------------------------

    static ProjectGraphInput rebuild(ProjectGraphInput base, List<SemanticDeclarationInput> semantics,
            List<LoweredDeclarationInput> lowered, List<ArtifactInput> artifacts, List<FileInput> files,
            List<EdgeBinding> edges) {
        return new ProjectGraphInput(base.version(), base.sourceId(), base.projectDisplayName(), semantics, lowered,
                artifacts, files, edges);
    }

    static ProjectGraphInput withSemantics(ProjectGraphInput base, List<SemanticDeclarationInput> semantics) {
        return rebuild(base, semantics, base.loweredDeclarations(), base.artifacts(), base.files(), base.edges());
    }

    static ProjectGraphInput withLowered(ProjectGraphInput base, List<LoweredDeclarationInput> lowered) {
        return rebuild(base, base.semanticDeclarations(), lowered, base.artifacts(), base.files(), base.edges());
    }

    static ProjectGraphInput withArtifacts(ProjectGraphInput base, List<ArtifactInput> artifacts) {
        return rebuild(base, base.semanticDeclarations(), base.loweredDeclarations(), artifacts, base.files(),
                base.edges());
    }

    static ProjectGraphInput withFiles(ProjectGraphInput base, List<FileInput> files) {
        return rebuild(base, base.semanticDeclarations(), base.loweredDeclarations(), base.artifacts(), files,
                base.edges());
    }

    static ProjectGraphInput withEdges(ProjectGraphInput base, List<EdgeBinding> edges) {
        return rebuild(base, base.semanticDeclarations(), base.loweredDeclarations(), base.artifacts(), base.files(),
                edges);
    }

    static <T> List<T> append(List<T> values, T extra) {
        List<T> copy = new ArrayList<>(values);
        copy.add(extra);
        return List.copyOf(copy);
    }

    static <T> List<T> replace(List<T> values, int index, T replacement) {
        List<T> copy = new ArrayList<>(values);
        copy.set(index, replacement);
        return List.copyOf(copy);
    }

    static <T> List<T> drop(List<T> values, Predicate<T> predicate) {
        return values.stream().filter(predicate.negate()).toList();
    }

    static <T> T first(List<T> values, Predicate<T> predicate) {
        return values.stream().filter(predicate).findFirst().orElseThrow(
                () -> new AssertionError("fixture has no element matching the requested predicate"));
    }

    // ---- identity helpers ----------------------------------------------------------------------

    static SymbolId symbol(String kind, String name) {
        return new SymbolId("sir://" + SOFTWARE_NAME + "/" + kind + "/" + name);
    }

    static LoweredNodeId loweredId(SymbolId symbol) {
        return new LoweredNodeId("lowered://" + symbol.value());
    }

    static LoweredNodeId artifactId(SymbolId symbol, ArtifactRole role) {
        return new LoweredNodeId("artifact://" + symbol.value() + "/" + role);
    }

    static GraphNodeId.Semantic semanticNode(SymbolId symbol) {
        return new GraphNodeId.Semantic(symbol);
    }

    static LoweredOrigin origin(SymbolId owner, AstNodeId sourceNodeId, SourceSpan span) {
        return new LoweredOrigin(Optional.of(owner), sourceNodeId, span);
    }

    static LoweredOrigin projectOrigin(String sourceNodeId) {
        return new LoweredOrigin(Optional.empty(), new AstNodeId(sourceNodeId), span(0));
    }

    static AstNodeId astId(SymbolId symbol) {
        return new AstNodeId("ast://" + symbol.value());
    }

    static SourceSpan span(int offset) {
        return new SourceSpan(SOURCE_ID,
                new SourcePosition(offset, 1, offset + 1),
                new SourcePosition(offset + 1, 1, offset + 2));
    }

    static String qualifiedName(String subpackage, String javaName) {
        return subpackage.isEmpty() ? BASE_PACKAGE + "." + javaName : BASE_PACKAGE + "." + subpackage + "." + javaName;
    }

    static String relativePath(String subpackage, String javaName) {
        String packagePath = subpackage.isEmpty() ? BASE_PACKAGE : BASE_PACKAGE + "." + subpackage;
        return "src/main/java/" + packagePath.replace('.', '/') + "/" + javaName + ".java";
    }

    /** A file input whose digest is derived from its own path, so the fixture stays deterministic. */
    static FileInput fileInput(String relativePath, LoweredNodeId artifactId, Optional<SymbolId> ownerSymbol) {
        byte[] payload = relativePath.getBytes(StandardCharsets.UTF_8);
        return new FileInput(relativePath, artifactId, ownerSymbol, payload.length, sha256Hex(payload));
    }

    static String sha256Hex(byte[] payload) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format(Locale.ROOT, "%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}

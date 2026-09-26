package io.kcg.sir.change;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.api.ParseResult;
import io.kcg.sir.api.SirParser;
import io.kcg.sir.api.SirSource;
import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.lowering.api.LoweredOrigin;
import io.kcg.sir.projectgraph.api.ArtifactRole;
import io.kcg.sir.projectgraph.api.GraphEdgeKind;
import io.kcg.sir.projectgraph.api.GraphNodeId;
import io.kcg.sir.projectgraph.api.GraphVersion;
import io.kcg.sir.projectgraph.api.ProjectGraph;
import io.kcg.sir.projectgraph.api.ProjectGraphAnalysis;
import io.kcg.sir.projectgraph.api.ProjectGraphBuilder;
import io.kcg.sir.projectgraph.api.ProjectGraphInput;
import io.kcg.sir.projectgraph.api.ProjectGraphInput.ArtifactInput;
import io.kcg.sir.projectgraph.api.ProjectGraphInput.EdgeBinding;
import io.kcg.sir.projectgraph.api.ProjectGraphInput.FileInput;
import io.kcg.sir.projectgraph.api.ProjectGraphInput.LoweredDeclarationInput;
import io.kcg.sir.projectgraph.api.ProjectGraphInput.SemanticDeclarationInput;
import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import io.kcg.sir.semantic.api.SemanticAnalysis;
import io.kcg.sir.semantic.api.SirSemanticAnalyzer;
import io.kcg.sir.semantic.model.NormalizedCapability;
import io.kcg.sir.semantic.model.NormalizedDeclaration;
import io.kcg.sir.semantic.model.NormalizedEntity;
import io.kcg.sir.semantic.model.NormalizedField;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.semantic.symbol.SymbolKind;
import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourcePosition;
import io.kcg.sir.source.SourceSpan;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Fixtures for the rename planner: a real semantic model from real source text, plus a hand-built
 * project graph shape.
 *
 * <p>The models come through the actual parser and semantic analyzer, so the identity the planner
 * sees is the identity the language produces — a fixture that hand-wrote {@code SymbolId} strings
 * could agree with the planner while disagreeing with the pipeline. The graphs stay hand-built
 * because {@code sir-change} must not depend on the generator; the fixture only has to reproduce the
 * graph facts the closure walks: a semantic declaration, its lowered declaration, the artifacts it
 * owns and the files those artifacts generate.
 */
final class RenameTestFixtures {
    static final SourceId SOURCE_ID = SourceId.of("rename.sir");
    static final String DECLARED_CAPABILITY_ID = "search-enrollments";

    static final String SHA_A = "1".repeat(64);
    static final String SHA_B = "2".repeat(64);
    static final String SHA_C = "3".repeat(64);
    static final String SHA_D = "4".repeat(64);
    static final String SHA_POM = "9".repeat(64);

    static final String SERVICE_PATH_BASE = "src/main/java/com/example/rename/application/SearchCourseEnrollmentsService.java";
    static final String SERVICE_PATH_SURVIVING = "src/main/java/com/example/rename/application/SearchEnrollmentsService.java";
    static final String CONTROLLER_PATH_BASE = "src/main/java/com/example/rename/api/SearchCourseEnrollmentsController.java";
    static final String CONTROLLER_PATH_CANDIDATE = "src/main/java/com/example/rename/api/SearchEnrollmentsController.java";

    private RenameTestFixtures() {
    }

    /** One legal program whose capability keeps a declared id while its name varies. */
    static String source(String capabilityName) {
        return """
                sir 0.1

                software Rename {
                  metadata {
                    displayName "Rename";
                    namespace "com.example.rename";
                  }
                  target {
                    language java 21;
                    framework spring_boot;
                    persistence mybatis_plus;
                    database mysql;
                    build maven;
                    interface rest;
                  }
                  declarations {
                    /* capability */
                    entity Course persistent {
                      identity id: Int64 generated auto;
                      field code: String where notBlank @id("course-code");
                    }
                    input GetCourseInput {
                      field id: Int64;
                    }
                    error CourseNotFound;
                    capability %s @id("%s") {
                      input GetCourseInput;
                      output Ref<Course>;
                      fails CourseNotFound;
                      requires readonly;
                      expose query;
                      workflow {
                        load Course by input.id as course else CourseNotFound;
                        return course;
                      }
                    }
                  }
                }
                """.formatted(capabilityName, DECLARED_CAPABILITY_ID);
    }

    /**
     * The same program with one comment replaced by another of equal length: every token keeps its
     * line, column and offset, so nothing a graph can see moves - only the source bytes do.
     */
    static String sourceWithOtherComment(String capabilityName) {
        String source = source(capabilityName);
        String edited = source.replace("/* capability */", "/* identities */");
        assertNotEquals(source, edited, "the comment edit must actually change the source");
        assertEquals(source.length(), edited.length(), "the comment edit must preserve every offset");
        return edited;
    }

    /** The same program without any declared id, for the legacy-identity cases. */
    static String sourceWithoutDeclaredIds(String capabilityName) {
        return source(capabilityName)
                .replace(" @id(\"course-code\")", "")
                .replace(" @id(\"" + DECLARED_CAPABILITY_ID + "\")", "");
    }

    static NormalizedSemanticModel model(String source) {
        ParseResult parsed = SirParser.create().parse(new SirSource(SOURCE_ID, source));
        assertTrue(parsed.isSuccess(), () -> "the rename fixture must parse: " + parsed.diagnostics());
        SemanticAnalysis analysis = new SirSemanticAnalyzer().analyze(parsed.document().orElseThrow());
        assertTrue(analysis.isSuccess(), () -> "the rename fixture must analyze: " + analysis.diagnostics());
        return analysis.model().orElseThrow();
    }

    static NormalizedCapability capability(NormalizedSemanticModel model) {
        for (NormalizedDeclaration declaration : model.declarations()) {
            if (declaration instanceof NormalizedCapability capability) {
                return capability;
            }
        }

        throw new IllegalStateException("no capability declaration in the model");
    }

    static NormalizedField entityField(NormalizedSemanticModel model) {
        for (NormalizedDeclaration declaration : model.declarations()) {
            if (declaration instanceof NormalizedEntity entity) {
                return entity.fields().getFirst();
            }
        }

        throw new IllegalStateException("no entity declaration in the model");
    }

    static GraphFixture graph(SymbolId capabilitySymbol, String capabilityName) {
        return new GraphFixture(capabilitySymbol, capabilityName);
    }

    /** One generated artifact: its lowering identity, role, qualified name, path and file digest. */
    private record ArtifactSpec(
            LoweredNodeId artifactId,
            ArtifactRole.DeclarationRole role,
            String qualifiedName,
            String relativePath,
            String sha256Hex
    ) {
    }

    /** A second declaration that generates a path of its own, used to break path exclusivity. */
    private record ForeignSpec(
            SymbolId symbol,
            String name,
            LoweredNodeId artifactId,
            String relativePath,
            String sha256Hex
    ) {
    }

    static final class GraphFixture {
        private final SymbolId capabilitySymbol;
        private final String capabilityName;
        private final List<ArtifactSpec> artifacts = new ArrayList<>();
        private final List<ForeignSpec> foreign = new ArrayList<>();
        private final java.util.Map<String, LoweredNodeId> provenanceOverrides = new java.util.LinkedHashMap<>();
        private String pomSha256Hex = SHA_POM;

        /** The digest of the project's own pom.xml, so a test can move a file no declaration owns. */
        GraphFixture pomDigest(String sha256Hex) {
            this.pomSha256Hex = sha256Hex;
            return this;
        }

        private GraphFixture(SymbolId capabilitySymbol, String capabilityName) {
            this.capabilitySymbol = capabilitySymbol;
            this.capabilityName = capabilityName;
        }

        /** The capability's main artifact: the path the rename withdraws and re-establishes. */
        GraphFixture service(LoweredNodeId artifactId, String qualifiedName, String relativePath, String sha256Hex) {
            return artifact(artifactId, ArtifactRole.DeclarationRole.SERVICE, qualifiedName, relativePath, sha256Hex);
        }

        /** The capability's exposure artifact: a second managed file under the same declaration. */
        GraphFixture controller(LoweredNodeId artifactId, String qualifiedName, String relativePath, String sha256Hex) {
            return artifact(artifactId, ArtifactRole.DeclarationRole.CONTROLLER, qualifiedName, relativePath, sha256Hex);
        }

        GraphFixture artifact(
                LoweredNodeId artifactId,
                ArtifactRole.DeclarationRole role,
                String qualifiedName,
                String relativePath,
                String sha256Hex
        ) {
            artifacts.add(new ArtifactSpec(artifactId, role, qualifiedName, relativePath, sha256Hex));
            return this;
        }

        /** Adds an unrelated declaration whose artifact generates the given path as well. */
        GraphFixture foreignOwner(LoweredNodeId artifactId, SymbolId symbol, String name, String relativePath, String sha256Hex) {
            foreign.add(new ForeignSpec(symbol, name, artifactId, relativePath, sha256Hex));
            return this;
        }

        /**
         * Materializes a graph whose file provenance names {@code provenanceArtifactId} while the
         * GENERATES_FILE edge comes from {@code generatorArtifactId} — the inconsistent graph the
         * withdrawal-ownership check exists for.
         */
        GraphFixture mismatchedFileProvenance(LoweredNodeId generatorArtifactId, String relativePath, LoweredNodeId provenanceArtifactId) {
            artifacts.stream()
                    .filter(spec -> spec.artifactId().equals(generatorArtifactId) && spec.relativePath().equals(relativePath))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("no artifact generates " + relativePath));
            provenanceOverrides.put(relativePath, provenanceArtifactId);
            return this;
        }

        ProjectGraph build() {
            List<SemanticDeclarationInput> semantics = new ArrayList<>();
            List<LoweredDeclarationInput> lowered = new ArrayList<>();
            List<ArtifactInput> artifactInputs = new ArrayList<>();
            List<FileInput> files = new ArrayList<>();
            List<EdgeBinding> edges = new ArrayList<>();

            addDeclaration(semantics, lowered, artifactInputs, files, edges, capabilitySymbol, capabilityName, artifacts);

            for (ForeignSpec spec : foreign) {
                addDeclaration(
                        semantics,
                        lowered,
                        artifactInputs,
                        files,
                        edges,
                        spec.symbol(),
                        spec.name(),
                        List.of(new ArtifactSpec(
                                spec.artifactId(),
                                ArtifactRole.DeclarationRole.SERVICE,
                                "com.example.rename.application." + spec.name() + "Service",
                                spec.relativePath(),
                                spec.sha256Hex()
                        ))
                );
            }

            // A project-level artifact and file: managed by the base manifest, owned by no declaration.
            LoweredNodeId pomArtifactId = new LoweredNodeId("lir://spring/maven/project");
            artifactInputs.add(new ArtifactInput(
                    pomArtifactId,
                    Optional.empty(),
                    projectOrigin(),
                    ArtifactRole.ProjectRole.MAVEN_PROJECT,
                    "rename"
            ));
            edges.add(new EdgeBinding(
                    GraphEdgeKind.OWNS_ARTIFACT, GraphNodeId.ProjectNodeId.INSTANCE, new GraphNodeId.Lowered(pomArtifactId)
            ));
            files.add(new FileInput("pom.xml", pomArtifactId, Optional.empty(), 10L, this.pomSha256Hex));
            edges.add(new EdgeBinding(
                    GraphEdgeKind.GENERATES_FILE, new GraphNodeId.Lowered(pomArtifactId), new GraphNodeId.File("pom.xml")
            ));

            ProjectGraphInput input = new ProjectGraphInput(
                    GraphVersion.V0_1, SOURCE_ID, "Rename", semantics, lowered, artifactInputs, files, edges
            );
            if (!provenanceOverrides.isEmpty()) {
                List<FileInput> rewritten = new ArrayList<>();
                for (FileInput file : files) {
                    LoweredNodeId override = provenanceOverrides.get(file.relativePath());
                    rewritten.add(override == null
                            ? file
                            : new FileInput(file.relativePath(), override, file.ownerSymbol(), file.byteCount(), file.sha256Hex()));
                }

                files = rewritten;
                input = new ProjectGraphInput(
                        GraphVersion.V0_1, SOURCE_ID, "Rename", semantics, lowered, artifactInputs, files, edges
                );
            }

            ProjectGraphAnalysis analysis = new ProjectGraphBuilder().build(input);
            ProjectGraphAnalysis.Success success = assertInstanceOf(
                    ProjectGraphAnalysis.Success.class, analysis, () -> "graph fixture must build: " + analysis.diagnostics());
            return success.graph();
        }

        private static void addDeclaration(
                List<SemanticDeclarationInput> semantics,
                List<LoweredDeclarationInput> lowered,
                List<ArtifactInput> artifactInputs,
                List<FileInput> files,
                List<EdgeBinding> edges,
                SymbolId symbol,
                String name,
                List<ArtifactSpec> artifacts
        ) {
            AstNodeId sourceNodeId = new AstNodeId("rename/" + name);
            semantics.add(new SemanticDeclarationInput(symbol, SymbolKind.CAPABILITY, name, sourceNodeId, span()));
            edges.add(new EdgeBinding(
                    GraphEdgeKind.DECLARES, GraphNodeId.ProjectNodeId.INSTANCE, new GraphNodeId.Semantic(symbol)
            ));
            LoweredNodeId loweredId = new LoweredNodeId("lir://spring/capability/" + name);
            lowered.add(new LoweredDeclarationInput(loweredId, symbol, name, new LoweredOrigin(Optional.of(symbol), sourceNodeId, span())));
            edges.add(new EdgeBinding(
                    GraphEdgeKind.LOWERS_TO, new GraphNodeId.Semantic(symbol), new GraphNodeId.Lowered(loweredId)
            ));

            for (ArtifactSpec artifact : artifacts) {
                artifactInputs.add(new ArtifactInput(
                        artifact.artifactId(),
                        Optional.of(symbol),
                        new LoweredOrigin(Optional.of(symbol), sourceNodeId, span()),
                        artifact.role(),
                        artifact.qualifiedName()
                ));
                edges.add(new EdgeBinding(
                        GraphEdgeKind.OWNS_ARTIFACT, new GraphNodeId.Lowered(loweredId), new GraphNodeId.Lowered(artifact.artifactId())
                ));
                files.add(new FileInput(
                        artifact.relativePath(), artifact.artifactId(), Optional.of(symbol), 100L, artifact.sha256Hex()
                ));
                edges.add(new EdgeBinding(
                        GraphEdgeKind.GENERATES_FILE, new GraphNodeId.Lowered(artifact.artifactId()), new GraphNodeId.File(artifact.relativePath())
                ));
            }
        }
    }

    private static SourceSpan span() {
        SourcePosition position = new SourcePosition(0, 1, 1);
        return new SourceSpan(SOURCE_ID, position, position);
    }

    private static LoweredOrigin projectOrigin() {
        SourcePosition position = new SourcePosition(0, 1, 1);
        return new LoweredOrigin(Optional.empty(), new AstNodeId("project"), new SourceSpan(SourceId.of("project"), position, position));
    }
}

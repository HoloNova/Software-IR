package io.kcg.sir.change;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.change.api.ArtifactAddition;
import io.kcg.sir.change.api.ArtifactChange;
import io.kcg.sir.change.api.ChangeBaseRevision;
import io.kcg.sir.change.api.ChangeIrVersion;
import io.kcg.sir.change.api.ChangeOperation;
import io.kcg.sir.change.api.ChangePlan;
import io.kcg.sir.change.api.ChangeSet;
import io.kcg.sir.change.api.ChangeTarget;
import io.kcg.sir.change.api.FileAddition;
import io.kcg.sir.change.api.FileChange;
import io.kcg.sir.change.api.ImpactedArtifact;
import io.kcg.sir.change.api.ModifyCapabilityWorkflow;
import io.kcg.sir.change.api.RenameFileEstablishment;
import io.kcg.sir.change.api.RenameFileUpdate;
import io.kcg.sir.change.api.RenameFileWithdrawal;
import io.kcg.sir.change.api.RenamePlan;
import io.kcg.sir.change.api.RenamePlanner;
import io.kcg.sir.change.api.RenameSubject;
import io.kcg.sir.change.api.RenameSubjectKind;
import io.kcg.sir.change.internal.RenamePlanDigest;
import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.projectgraph.api.ArtifactRole;
import io.kcg.sir.projectgraph.api.GraphVersion;
import io.kcg.sir.projectgraph.api.ProjectGraphCanonicalFormatVersion;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.source.SourceId;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The invariants that keep a rename plan read-only and keep the Change IR intact (Q16).
 *
 * <p>Three things are pinned here: a plan is a value (ordered, disjoint, digested, and impossible to
 * apply — the planner's whole public surface is {@code plan} and {@code verify}); the rename code
 * touches neither the disk nor the existing apply path; and the six {@code ChangeOperation} families
 * plus the one-family-per-{@code ChangePlan} rule are exactly as they were, so a rename plan can
 * never be confused with a change plan.
 */
class RenamePlanContractTest {
    private static final SymbolId SUBJECT = new SymbolId("sir://Rename/declared/capability/search-enrollments");
    private static final LoweredNodeId ARTIFACT = new LoweredNodeId("lir://spring/service/search-enrollments");
    private static final String PATH_A = "src/main/java/com/example/rename/application/SearchEnrollmentsService.java";
    private static final String PATH_B = "src/main/java/com/example/rename/api/SearchEnrollmentsController.java";
    private static final String PATH_C = "src/main/java/com/example/rename/api/SearchController.java";

    @Test
    void theRenamesPublicSurfaceIsPlanAndVerifyOnly() {
        Set<String> names = new LinkedHashSet<>();
        for (Method method : RenamePlanner.class.getDeclaredMethods()) {
            names.add(method.getName());
        }

        assertEquals(Set.of("plan", "verify"), names);
        for (String name : names) {
            assertFalse(
                    name.matches(".*(apply|write|execute|commit|delete|remove|publish|recover).*"),
                    "the rename planner must expose no way to act on a plan: " + name
            );
        }
    }

    @Test
    void theRenameSourcesDoNotTouchDiskOrTheChangeApplyPath() throws Exception {
        Path renameSources = renameSourceRoot();
        int scanned = 0;
        for (Path javaFile : javaSources(renameSources)) {
            String content = codeOnly(Files.readString(javaFile));
            scanned++;
            for (String forbidden : List.of("java.nio.file", "ChangePlanner", "ConflictPolicy", "REPLACE_EXISTING", "ChangeExecutionApplication")) {
                assertFalse(content.contains(forbidden),
                        "rename planning must stay read-only and outside the change apply path: " + javaFile + " mentions " + forbidden);
            }
        }

        assertTrue(scanned >= 3, "expected the rename api and core sources to be scanned, found " + scanned);
    }

    /**
     * Code only: the javadoc states which existing paths this unit must <em>not</em> use, so naming
     * them in documentation is the point rather than a violation.
     */
    private static String codeOnly(String source) {
        return source.lines()
                .map(String::stripLeading)
                .filter(line -> !line.startsWith("*") && !line.startsWith("//") && !line.startsWith("/*"))
                .reduce("", (left, right) -> left + right + "\n");
    }

    @Test
    void aPlanRejectsUnsortedOrOverlappingSets() {
        RenameFileUpdate applicationPath = update(PATH_A);
        RenameFileUpdate apiPath = update(PATH_B);
        assertTrue(PATH_B.compareTo(PATH_A) < 0, "the fixture must know which path sorts first");

        assertThrows(IllegalArgumentException.class,
                () -> new RenamePlan(baseRevision(), subject(), List.of(applicationPath, apiPath), List.of(), List.of(), DIGEST, DIGEST, DIGEST, DIGEST, DIGEST));
        assertThrows(IllegalArgumentException.class,
                () -> new RenamePlan(baseRevision(), subject(), List.of(applicationPath), List.of(withdrawal(PATH_A)), List.of(), DIGEST, DIGEST, DIGEST, DIGEST, DIGEST));
        assertThrows(IllegalArgumentException.class,
                () -> new RenamePlan(baseRevision(), subject(), List.of(applicationPath), List.of(), List.of(establishment(PATH_A)), DIGEST, DIGEST, DIGEST, DIGEST, DIGEST));
        assertThrows(IllegalArgumentException.class,
                () -> new RenamePlan(baseRevision(), subject(), List.of(applicationPath), List.of(), List.of(), "not-a-digest", DIGEST, DIGEST, DIGEST, DIGEST));
    }

    @Test
    void aPlanAcceptsDisjointSortedSets() {
        RenamePlan plan = new RenamePlan(
                baseRevision(),
                subject(),
                List.of(update(PATH_A)),
                List.of(withdrawal(PATH_B)),
                List.of(establishment(PATH_C)),
                DIGEST,
                DIGEST,
                DIGEST,
                DIGEST,
                DIGEST
        );

        assertEquals(List.of(PATH_A), plan.updates().stream().map(RenameFileUpdate::relativePath).toList());
        assertEquals(List.of(PATH_B), plan.withdrawals().stream().map(RenameFileWithdrawal::relativePath).toList());
        assertEquals(List.of(PATH_C), plan.establishments().stream().map(RenameFileEstablishment::relativePath).toList());
    }

    @Test
    void thePlanDigestCoversEveryClaimedFact() {
        RenamePlan plan = new RenamePlan(
                baseRevision(), subject(), List.of(update(PATH_A)), List.of(withdrawal(PATH_B)), List.of(), DIGEST, DIGEST, DIGEST, DIGEST, DIGEST
        );
        RenamePlan differentName = new RenamePlan(
                baseRevision(),
                new RenameSubject(SUBJECT, RenameSubjectKind.CAPABILITY, "SearchCourseEnrollments", "SearchCatalog"),
                List.of(update(PATH_A)),
                List.of(withdrawal(PATH_B)),
                List.of(),
                DIGEST,
                DIGEST,
                DIGEST,
                DIGEST,
                DIGEST
        );
        RenamePlan differentFile = new RenamePlan(
                baseRevision(),
                subject(),
                List.of(update(PATH_C)),
                List.of(withdrawal(PATH_B)),
                List.of(),
                DIGEST,
                DIGEST,
                DIGEST,
                DIGEST,
                DIGEST
        );
        // Same graph digests, different source bytes: only the source binding changed.
        RenamePlan differentBaseSource = new RenamePlan(
                baseRevision(),
                subject(),
                List.of(update(PATH_A)),
                List.of(withdrawal(PATH_B)),
                List.of(),
                DIGEST,
                DIGEST,
                "b".repeat(64),
                DIGEST,
                DIGEST
        );

        String digest = RenamePlanDigest.of(plan);
        assertTrue(digest.matches("[0-9a-f]{64}"), digest);
        assertEquals(digest, RenamePlanDigest.of(plan), "the digest must be deterministic");
        assertNotEquals(digest, RenamePlanDigest.of(differentName));
        assertNotEquals(digest, RenamePlanDigest.of(differentFile));
        assertNotEquals(digest, RenamePlanDigest.of(differentBaseSource));
    }

    @Test
    void aRenamePlanIsNotAChangePlanAndTheSixFamiliesAreUnchanged() {
        assertFalse(ChangeOperation.class.isAssignableFrom(RenamePlan.class));
        assertEquals(6, ChangeOperation.class.getPermittedSubclasses().length);

        ChangeSet changeSet = new ChangeSet(
                ChangeIrVersion.V0_1, baseRevision(), List.of(new ModifyCapabilityWorkflow(changeTarget()))
        );
        FileChange fileChange = new FileChange(
                PATH_A, ARTIFACT, Optional.of(SUBJECT), 1L, DIGEST, 2L, "b".repeat(64)
        );
        ArtifactChange artifactChange = new ArtifactChange(
                new ImpactedArtifact(ARTIFACT, Optional.of(SUBJECT), ArtifactRole.DeclarationRole.SERVICE, "x.YService", List.of(fileChange)),
                List.of(fileChange)
        );
        FileAddition fileAddition = new FileAddition(PATH_B, ARTIFACT, SUBJECT, 1L, DIGEST);
        ArtifactAddition artifactAddition = new ArtifactAddition(
                ARTIFACT, SUBJECT, ArtifactRole.DeclarationRole.SERVICE, "x.YService", List.of(fileAddition)
        );

        IllegalArgumentException mixed = assertThrows(
                IllegalArgumentException.class,
                () -> new ChangePlan(
                        changeSet,
                        List.of(artifactChange),
                        List.of(fileChange),
                        List.of(artifactAddition),
                        List.of(fileAddition),
                        List.of(),
                        List.of()
                )
        );
        assertTrue(mixed.getMessage().contains("must not mix"), mixed.getMessage());
        assertNotNull(new ChangePlan(changeSet, List.of(artifactChange), List.of(fileChange)));
    }

    /** The rename api and internal sources, which this unit added. */
    private static Path renameSourceRoot() {
        Path mainSrc = Path.of("src/main/java");
        if (!Files.exists(mainSrc)) {
            mainSrc = Path.of(System.getProperty("user.dir"), "src", "main", "java");
        }

        assertTrue(Files.exists(mainSrc), "sir-change main source root must exist: " + mainSrc.toAbsolutePath());
        return mainSrc.resolve("io/kcg/sir/change");
    }

    private static List<Path> javaSources(Path root) throws Exception {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> path.getFileName().toString().startsWith("Rename"))
                    .toList();
        }
    }

    private static final String DIGEST = "a".repeat(64);

    private static RenameFileUpdate update(String path) {
        return new RenameFileUpdate(path, ARTIFACT, Optional.of(SUBJECT), 1L, DIGEST, 2L, "b".repeat(64));
    }

    private static RenameFileWithdrawal withdrawal(String path) {
        return new RenameFileWithdrawal(path, ARTIFACT, Optional.of(SUBJECT), 1L, DIGEST);
    }

    private static RenameFileEstablishment establishment(String path) {
        return new RenameFileEstablishment(path, ARTIFACT, Optional.of(SUBJECT), 1L, DIGEST);
    }

    private static RenameSubject subject() {
        return new RenameSubject(SUBJECT, RenameSubjectKind.CAPABILITY, "SearchCourseEnrollments", "SearchEnrollments");
    }

    private static ChangeTarget changeTarget() {
        return new ChangeTarget(SUBJECT, new AstNodeId("rename/SearchEnrollments"), new AstNodeId("rename/SearchEnrollments/workflow"));
    }

    private static ChangeBaseRevision baseRevision() {
        return new ChangeBaseRevision(
                SourceId.of("rename.sir"), DIGEST, GraphVersion.V0_1, DIGEST, ProjectGraphCanonicalFormatVersion.V1
        );
    }
}

package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.application.api.ChangeBaselineRegistrationResult;
import io.kcg.sir.application.api.ChangeExecutionApplication;
import io.kcg.sir.application.api.GeneratedBaselineRegistrationRequest;
import io.kcg.sir.application.api.RenameApplyRequest;
import io.kcg.sir.application.api.RenameApplyResult;
import io.kcg.sir.application.internal.SirCompiler;
import io.kcg.sir.application.internal.state.JournalGate;
import io.kcg.sir.semantic.model.NormalizedCapability;
import io.kcg.sir.semantic.model.NormalizedDeclaration;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.source.SourceId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RenameApplyEndToEndTest {
   @TempDir Path temp;

   @Test
   void appliesRenameAtomicallyAndProducesTheFromScratchCandidateTree() throws Exception {
      String baseText = RenamePreflightTestSupport.baseSource();
      String candidateText = RenamePreflightTestSupport.candidateSource();
      assertEquals(baseText.replace(RenamePreflightTestSupport.BASE_NAME, RenamePreflightTestSupport.CANDIDATE_NAME), candidateText);
      Fixture f = fixture(temp, baseText, "rename-course-search.sir");
      Path candidate = temp.resolve("candidate.sir");
      Files.writeString(candidate, candidateText, StandardCharsets.UTF_8);
      Path freshDir = temp.resolve("from-scratch");
      Files.createDirectories(freshDir);
      Path freshRoot = RenamePreflightTestSupport.candidate(freshDir).root();
      Map<String, String> b0Tree = RenamePreflightTestSupport.inventory(f.outputRoot());

      RenameApplyResult.Applied applied = assertInstanceOf(RenameApplyResult.Applied.class,
         f.application().applyRename(new RenameApplyRequest(f.stateRoot(), f.baselineId(), candidate, f.outputRoot(), f.subject())));
      assertEquals(f.baselineId(), applied.baseBaselineReceipt().baselineId());
      assertNotEquals(f.baselineId(), applied.newBaselineReceipt().baselineId());
      assertTrue(applied.transactionCleaned());
      assertEquals(RenamePreflightTestSupport.inventory(freshRoot), RenamePreflightTestSupport.inventory(f.outputRoot()));
      assertFalse(Files.exists(f.outputRoot().resolve("src/main/java/com/example/rename/api/SearchCourseEnrollmentsController.java")));
      assertFalse(Files.exists(f.outputRoot().resolve("src/main/java/com/example/rename/application/SearchCourseEnrollmentsService.java")));
      assertTrue(Files.exists(f.outputRoot().resolve("src/main/java/com/example/rename/api/SearchEnrollmentsController.java")));
      assertTrue(Files.exists(f.outputRoot().resolve("src/main/java/com/example/rename/application/SearchEnrollmentsService.java")));
      Map<String, String> afterRename = RenamePreflightTestSupport.inventory(f.outputRoot());
      for (Map.Entry<String, String> entry : b0Tree.entrySet()) {
         if (!applied.appliedFiles().contains(entry.getKey()))
            assertEquals(entry.getValue(), afterRename.get(entry.getKey()), "untouched: " + entry.getKey());
      }
      assertEquals(applied.newBaselineReceipt().baselineId(), Files.readString(f.stateRoot().resolve("CURRENT")).trim());
      assertFalse(Files.exists(f.stateRoot().resolve("CURRENT.new")));
      assertTrue(new JournalGate(f.stateRoot()).inspect(Optional.of(applied.newBaselineReceipt().baselineId())).isOpen());
      Path transactions = f.stateRoot().resolve("transactions");
      if (Files.exists(transactions)) try (var entries = Files.list(transactions)) { assertEquals(0L, entries.count()); }
      Map<String, String> outputAfter = RenamePreflightTestSupport.inventory(f.outputRoot());
      Map<String, String> stateAfter = RenamePreflightTestSupport.inventory(f.stateRoot());
      RenameApplyResult.NoChanges repeated = assertInstanceOf(RenameApplyResult.NoChanges.class,
         f.application().applyRename(new RenameApplyRequest(f.stateRoot(), applied.newBaselineReceipt().baselineId(), candidate, f.outputRoot(), f.subject())));
      assertEquals(applied.newBaselineReceipt().baselineId(), repeated.currentBaselineReceipt().baselineId());
      assertEquals(outputAfter, RenamePreflightTestSupport.inventory(f.outputRoot()));
      assertEquals(stateAfter, RenamePreflightTestSupport.inventory(f.stateRoot()));
   }

   @Test
   void outOfScopeInputRenameIsRefusedWithoutMutation() throws Exception {
      String original = ApplicationTestSupport.resource("valid/course-admin-enrollment.sir");
      String from = "SearchCourseEnrollments", to = "SearchEnrollments";
      assertEquals(3, occurrences(original, from));
      String base = original.replace("capability " + from + " {", "capability " + from + " @id(\"search-course-enrollments\") {");
      Fixture f = fixture(temp, base, "course-admin-enrollment.sir");
      Path candidate = temp.resolve("course-candidate.sir");
      Files.writeString(candidate, base.replace(from, to), StandardCharsets.UTF_8);
      Map<String, String> outputBefore = RenamePreflightTestSupport.inventory(f.outputRoot());
      Map<String, String> stateBefore = RenamePreflightTestSupport.inventory(f.stateRoot());
      RenameApplyResult.Failure refused = assertInstanceOf(RenameApplyResult.Failure.class,
         f.application().applyRename(new RenameApplyRequest(f.stateRoot(), f.baselineId(), candidate, f.outputRoot(), f.subject())));
      assertTrue(refused.diagnostics().stream().anyMatch(d -> d.code().equals("SIR-RENAME-PATH-004")), refused.diagnostics().toString());
      assertEquals(outputBefore, RenamePreflightTestSupport.inventory(f.outputRoot()));
      assertEquals(stateBefore, RenamePreflightTestSupport.inventory(f.stateRoot()));
   }

   @Test
   void blockedJournalReturnsRecoveryRequiredWithoutProceeding() throws Exception {
      Fixture f = fixture(temp, RenamePreflightTestSupport.baseSource(), "rename-course-search.sir");
      Path candidate = temp.resolve("blocked-candidate.sir");
      Files.writeString(candidate, RenamePreflightTestSupport.candidateSource(), StandardCharsets.UTF_8);
      Path transaction = f.stateRoot().resolve("transactions/blocked");
      Files.createDirectories(transaction);
      Files.writeString(transaction.resolve("journal"), "unknown journal family\\n", StandardCharsets.UTF_8);
      Map<String, String> outputBefore = RenamePreflightTestSupport.inventory(f.outputRoot());
      Map<String, String> stateBefore = RenamePreflightTestSupport.inventory(f.stateRoot());
      RenameApplyResult.RecoveryRequired blocked = assertInstanceOf(RenameApplyResult.RecoveryRequired.class,
         f.application().applyRename(new RenameApplyRequest(f.stateRoot(), f.baselineId(), candidate, f.outputRoot(), f.subject())));
      assertTrue(blocked.diagnostics().stream().anyMatch(d -> d.code().startsWith("SIR-APP-CHANGE-RECOVERY-")), blocked.diagnostics().toString());
      assertEquals(outputBefore, RenamePreflightTestSupport.inventory(f.outputRoot()));
      assertEquals(stateBefore, RenamePreflightTestSupport.inventory(f.stateRoot()));
      assertEquals(f.baselineId(), Files.readString(f.stateRoot().resolve("CURRENT")).trim());
   }

   @Test
   void occupiedEstablishmentAndWrongBaselineAreRefusedWithoutMutation() throws Exception {
      String base = RenamePreflightTestSupport.baseSource();
      Fixture occupied = fixture(temp.resolve("occupied"), base, "rename-course-search.sir");
      Path candidate = temp.resolve("occupied-candidate.sir");
      Files.writeString(candidate, RenamePreflightTestSupport.candidateSource(), StandardCharsets.UTF_8);
      Path collision = occupied.outputRoot().resolve("src/main/java/com/example/rename/api/SearchEnrollmentsController.java");
      Files.createDirectories(collision.getParent());
      Files.writeString(collision, "external");
      Map<String, String> outBefore = RenamePreflightTestSupport.inventory(occupied.outputRoot());
      Map<String, String> stateBefore = RenamePreflightTestSupport.inventory(occupied.stateRoot());
      RenameApplyResult.Failure collisionResult = assertInstanceOf(RenameApplyResult.Failure.class,
         occupied.application().applyRename(new RenameApplyRequest(occupied.stateRoot(), occupied.baselineId(), candidate, occupied.outputRoot(), occupied.subject())));
      assertTrue(collisionResult.diagnostics().stream().anyMatch(d -> d.code().equals("SIR-APP-CHANGE-PROTECT-111")), collisionResult.diagnostics().toString());
      assertEquals(outBefore, RenamePreflightTestSupport.inventory(occupied.outputRoot()));
      assertEquals(stateBefore, RenamePreflightTestSupport.inventory(occupied.stateRoot()));

      Fixture wrong = fixture(temp.resolve("wrong"), base, "rename-course-search.sir");
      Path wrongCandidate = temp.resolve("wrong-candidate.sir");
      Files.writeString(wrongCandidate, RenamePreflightTestSupport.candidateSource(), StandardCharsets.UTF_8);
      Map<String, String> wrongOutBefore = RenamePreflightTestSupport.inventory(wrong.outputRoot());
      Map<String, String> wrongStateBefore = RenamePreflightTestSupport.inventory(wrong.stateRoot());
      RenameApplyResult.Failure wrongResult = assertInstanceOf(RenameApplyResult.Failure.class,
         wrong.application().applyRename(new RenameApplyRequest(wrong.stateRoot(), "0".repeat(64), wrongCandidate, wrong.outputRoot(), wrong.subject())));
      assertTrue(wrongResult.diagnostics().stream().anyMatch(d -> d.message().contains("expectedBaselineId mismatch")), wrongResult.diagnostics().toString());
      assertEquals(wrongOutBefore, RenamePreflightTestSupport.inventory(wrong.outputRoot()));
      assertEquals(wrongStateBefore, RenamePreflightTestSupport.inventory(wrong.stateRoot()));
   }

   private Fixture fixture(Path root, String sourceText, String sourceName) throws Exception {
      Files.createDirectories(root);
      SourceId sourceId = SourceId.of(sourceName);
      Path source = root.resolve("base.sir");
      Files.writeString(source, sourceText, StandardCharsets.UTF_8);
      Path output = root.resolve("out");
      var generated = new io.kcg.sir.application.api.ToolchainApplication().execute(
         new io.kcg.sir.application.api.ToolchainRequest(source, sourceId, output, io.kcg.sir.application.api.ConflictPolicy.FAIL_IF_EXISTS));
      assertInstanceOf(io.kcg.sir.application.api.ToolchainResult.Success.class, generated);
      Path state = root.resolve("state");
      ChangeExecutionApplication application = new ChangeExecutionApplication();
      ChangeBaselineRegistrationResult.Success registration = assertInstanceOf(ChangeBaselineRegistrationResult.Success.class,
         application.registerGeneratedBaseline(new GeneratedBaselineRegistrationRequest(source, sourceId, output, state)));
      ArrayList<io.kcg.sir.application.api.ExecutionDiagnostic> errors = new ArrayList<>();
      var compiled = SirCompiler.compile(sourceText, sourceId, errors).orElseThrow(() -> new AssertionError(errors));
       SymbolId subject = compiled.semanticModel().declarations().stream().filter(NormalizedCapability.class::isInstance)
          .map(NormalizedCapability.class::cast)
          .filter(capability -> capability.id().value().endsWith("/declared/capability/search-course-enrollments"))
          .map(NormalizedCapability::id).findFirst().orElseThrow();
      return new Fixture(application, output, state, registration.receipt().baselineId(), subject);
   }

   private static int occurrences(String input, String token) {
      int count = 0, offset = 0;
      while ((offset = input.indexOf(token, offset)) >= 0) { count++; offset += token.length(); }
      return count;
   }

   private record Fixture(ChangeExecutionApplication application, Path outputRoot, Path stateRoot, String baselineId, SymbolId subject) {}
}

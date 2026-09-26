package io.kcg.sir.change.internal;

import io.kcg.sir.change.api.ChangeBaseRevision;
import io.kcg.sir.change.api.RenameAnalysis;
import io.kcg.sir.change.api.RenameDiagnostic;
import io.kcg.sir.change.api.RenameDiagnosticStage;
import io.kcg.sir.change.api.RenameFileEstablishment;
import io.kcg.sir.change.api.RenameFileUpdate;
import io.kcg.sir.change.api.RenameFileWithdrawal;
import io.kcg.sir.change.api.RenameNoChangeReason;
import io.kcg.sir.change.api.RenamePlan;
import io.kcg.sir.change.api.RenamePlanRequest;
import io.kcg.sir.change.api.RenamePlanningInput;
import io.kcg.sir.change.api.RenameRevisionSnapshot;
import io.kcg.sir.change.api.RenameSubject;
import io.kcg.sir.change.api.RenameSubjectKind;
import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.projectgraph.api.ProjectGraph;
import io.kcg.sir.projectgraph.api.ProjectGraphCanonicalFormatVersion;
import io.kcg.sir.projectgraph.api.ProjectGraphNode;
import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import io.kcg.sir.semantic.model.NormalizedCapability;
import io.kcg.sir.semantic.model.NormalizedDeclaration;
import io.kcg.sir.semantic.model.NormalizedEntity;
import io.kcg.sir.semantic.model.NormalizedField;
import io.kcg.sir.semantic.symbol.DeclarationIdentity;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.source.SourceId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Plans renames of declarations with an explicit persistent id.
 *
 * <p>The reasoning order is the acceptance order: a rename exists only after the request names a
 * revision the base graph still matches, the identity is proven explicit, both sides hold that
 * identity, the old and new artifacts map one to one, and the three file sets derived from the two
 * managed closures are exclusive. Anything that cannot be proven out of those facts is rejected with
 * a specific code instead of being guessed from names or text similarity.
 */
public final class RenamePlannerCore {
   private RenamePlannerCore() {
   }

   public static RenameAnalysis plan(RenamePlanningInput input) {
      Objects.requireNonNull(input, "input");
      List<RenameDiagnostic> diagnostics = new ArrayList<>();
      RenamePlanRequest request = input.request();
      SymbolId declarationSymbol = request.declarationSymbol();

      checkRequest(input, diagnostics);
      if (hasError(diagnostics)) {
         return rejection(diagnostics);
      }

      if (!DeclarationIdentity.isDeclared(declarationSymbol)) {
         diagnostics.add(RenameDiagnostic.error(
            "SIR-RENAME-IDENTITY-001",
            RenameDiagnosticStage.IDENTITY,
            declarationSymbol,
            "改名计划要求显式持久身份（源中 @id），而这是名称派生的身份，无法证明改名: " + declarationSymbol.value()
         ));
         return rejection(diagnostics);
      }

      NamedDeclaration baseDeclaration = findDeclaration(input.baseModel(), declarationSymbol);
      if (baseDeclaration == null) {
         diagnostics.add(RenameDiagnostic.error(
            "SIR-RENAME-TARGET-001", RenameDiagnosticStage.TARGET, declarationSymbol, "基线中没有该持久身份的声明"
         ));
         return rejection(diagnostics);
      }

      NamedDeclaration candidateDeclaration = findDeclaration(input.candidateModel(), declarationSymbol);
      if (candidateDeclaration == null) {
         diagnostics.add(RenameDiagnostic.error(
            "SIR-RENAME-IDENTITY-002",
            RenameDiagnosticStage.IDENTITY,
            declarationSymbol,
            "候选缺少同一持久身份；只改名字而不保留 @id 不能被当作改名（基线名称: " + baseDeclaration.name() + "）"
         ));
         return rejection(diagnostics);
      }

      if (baseDeclaration.kind() == RenameSubjectKind.ENTITY_FIELD) {
         diagnostics.add(RenameDiagnostic.error(
            "SIR-RENAME-PHYSICAL-001",
            RenameDiagnosticStage.PHYSICAL,
            declarationSymbol,
            "实体字段的物理列名仍由字段名派生；列名继承与数据库迁移属于 G3，本单拒绝字段改名落盘"
         ));
         return rejection(diagnostics);
      }

      if (baseDeclaration.name().equals(candidateDeclaration.name())) {
         return new RenameAnalysis.NoRename(RenameNoChangeReason.NAME_UNCHANGED, List.of());
      }

      ClosureComputer.Closure baseClosure = closure(input.base().graph(), declarationSymbol, "基线", diagnostics);
      ClosureComputer.Closure candidateClosure = closure(input.candidate().graph(), declarationSymbol, "候选", diagnostics);
      if (baseClosure == null || candidateClosure == null) {
         return rejection(diagnostics);
      }

      checkArtifactMapping(baseClosure, candidateClosure, declarationSymbol, diagnostics);
      if (hasError(diagnostics)) {
         return rejection(diagnostics);
      }

      Map<String, ProjectGraphNode.ProjectFile> baseFiles = indexFiles(baseClosure);
      Map<String, ProjectGraphNode.ProjectFile> candidateFiles = indexFiles(candidateClosure);
      List<RenameFileUpdate> updates = new ArrayList<>();
      List<RenameFileEstablishment> establishments = new ArrayList<>();
      for (ProjectGraphNode.ProjectFile candidateFile : candidateClosure.files()) {
         ProjectGraphNode.ProjectFile baseFile = baseFiles.get(candidateFile.id().relativePath());
         if (baseFile == null) {
            establishments.add(establishment(candidateFile));
         } else {
            updates.add(update(baseFile, candidateFile));
         }
      }

      // Survivors and withdrawals come from the base closure, so both are base-managed paths by
      // construction; establishments are paths that manifest does not hold. An untracked file can
      // therefore never be named by a plan. Whether a path is free on disk is the apply unit's
      // precondition, not something a graph can answer.
      List<RenameFileWithdrawal> withdrawals = new ArrayList<>();
      Map<String, ProjectGraphNode.ProjectFile> baseManifest = indexAllFiles(input.base().graph());
      for (ProjectGraphNode.ProjectFile baseFile : baseClosure.files()) {
         String path = baseFile.id().relativePath();
         if (candidateFiles.containsKey(path)) {
            continue;
         }

         withdrawals.add(withdrawal(baseFile));
      }

      for (RenameFileEstablishment establishment : establishments) {
         if (baseManifest.containsKey(establishment.relativePath())) {
            diagnostics.add(RenameDiagnostic.errorForPath(
               "SIR-RENAME-PATH-002",
               RenameDiagnosticStage.PATH,
               declarationSymbol,
               establishment.relativePath(),
               "待建立路径已由基线受管清单占用，不能直接建立"
            ));
         }
      }

      checkExclusiveSets(updates, withdrawals, establishments, declarationSymbol, diagnostics);
      checkManagedFileCoverage(
         updates, withdrawals, establishments, input.base().graph(), input.candidate().graph(), declarationSymbol, diagnostics
      );
      if (hasError(diagnostics)) {
         return rejection(diagnostics);
      }

      RenameSubject subject = new RenameSubject(
         declarationSymbol, baseDeclaration.kind(), baseDeclaration.name(), candidateDeclaration.name()
      );
      String baseDigest = input.base().graph().canonicalDigest();
      String candidateDigest = input.candidate().graph().canonicalDigest();
      String planDigest = RenamePlanDigest.of(
         request.basedOn(),
         subject,
         updates,
         withdrawals,
         establishments,
         baseDigest,
         candidateDigest,
         input.base().source().sha256Hex(),
         input.candidate().source().sha256Hex()
      );
      RenamePlan plan = new RenamePlan(
         request.basedOn(),
         subject,
         updates,
         withdrawals,
         establishments,
         baseDigest,
         candidateDigest,
         input.base().source().sha256Hex(),
         input.candidate().source().sha256Hex(),
         planDigest
      );
      return new RenameAnalysis.Planned(plan, List.of());
   }

   public static List<RenameDiagnostic> verify(
      RenamePlan plan, RenameRevisionSnapshot base, RenameRevisionSnapshot candidate
   ) {
      List<RenameDiagnostic> diagnostics = new ArrayList<>();
      SymbolId declarationSymbol = plan.subject().declarationSymbol();
      checkSnapshotIdentity(base, declarationSymbol, "基线", diagnostics);
      checkSnapshotIdentity(candidate, declarationSymbol, "候选", diagnostics);
      if (!plan.baseGraphCanonicalDigest().equals(base.graph().canonicalDigest())) {
         diagnostics.add(RenameDiagnostic.error(
            "SIR-RENAME-STALE-001", RenameDiagnosticStage.STALE, declarationSymbol, "基线图已变化，计划不再对应当前基线"
         ));
      }

      if (!plan.baseSourceSha256Hex().equals(base.source().sha256Hex())) {
         diagnostics.add(RenameDiagnostic.error(
            "SIR-RENAME-STALE-004",
            RenameDiagnosticStage.STALE,
            declarationSymbol,
            "基线源字节已变化（图未必变化），计划不再对应当前基线源"
         ));
      }

      if (!plan.candidateGraphCanonicalDigest().equals(candidate.graph().canonicalDigest())) {
         diagnostics.add(RenameDiagnostic.error(
            "SIR-RENAME-STALE-002", RenameDiagnosticStage.STALE, declarationSymbol, "候选图已变化，计划不再对应当前候选"
         ));
      }

      if (!plan.candidateSourceSha256Hex().equals(candidate.source().sha256Hex())) {
         diagnostics.add(RenameDiagnostic.error(
            "SIR-RENAME-STALE-005",
            RenameDiagnosticStage.STALE,
            declarationSymbol,
            "候选源字节已变化（图未必变化），计划不再对应当前候选源"
         ));
      }

      if (!plan.planDigest().equals(RenamePlanDigest.of(plan))) {
         diagnostics.add(RenameDiagnostic.error(
            "SIR-RENAME-STALE-003", RenameDiagnosticStage.STALE, declarationSymbol, "计划摘要与计划内容不一致"
         ));
      }

      Map<String, ProjectGraphNode.ProjectFile> baseManifest = indexAllFiles(base.graph());
      for (RenameFileUpdate update : plan.updates()) {
         checkBaseFile(
            baseManifest.get(update.relativePath()),
            update.relativePath(),
            update.baseByteCount(),
            update.baseSha256Hex(),
            declarationSymbol,
            diagnostics
         );
      }

      for (RenameFileWithdrawal withdrawal : plan.withdrawals()) {
         checkBaseFile(
            baseManifest.get(withdrawal.relativePath()),
            withdrawal.relativePath(),
            withdrawal.byteCount(),
            withdrawal.sha256Hex(),
            declarationSymbol,
            diagnostics
         );
      }

      for (RenameFileEstablishment establishment : plan.establishments()) {
         if (baseManifest.containsKey(establishment.relativePath())) {
            diagnostics.add(RenameDiagnostic.errorForPath(
               "SIR-RENAME-PATH-002",
               RenameDiagnosticStage.PATH,
               declarationSymbol,
               establishment.relativePath(),
               "计划声称这是新路径，但基线受管清单已占用该路径"
            ));
         }
      }

      checkManagedFileCoverage(
         plan.updates(), plan.withdrawals(), plan.establishments(), base.graph(), candidate.graph(), declarationSymbol, diagnostics
      );
      return sortAndCopy(diagnostics);
   }

   private static void checkRequest(RenamePlanningInput input, List<RenameDiagnostic> diagnostics) {
      ChangeBaseRevision basedOn = input.request().basedOn();
      ProjectGraph baseGraph = input.base().graph();
      ProjectGraph candidateGraph = input.candidate().graph();
      if (basedOn.graphVersion() != baseGraph.version() || basedOn.graphVersion() != candidateGraph.version()) {
         diagnostics.add(RenameDiagnostic.error(
            "SIR-RENAME-REQUEST-001", RenameDiagnosticStage.REQUEST, null, "请求基线的图版本与输入图不一致"
         ));
      }

      if (basedOn.snapshotFormatVersion() != ProjectGraphCanonicalFormatVersion.V1) {
         diagnostics.add(RenameDiagnostic.error(
            "SIR-RENAME-REQUEST-002",
            RenameDiagnosticStage.REQUEST,
            null,
            "请求基线的规范快照格式不是 V1: " + basedOn.snapshotFormatVersion().name()
         ));
      }

      if (!basedOn.sourceId().equals(sourceIdOf(baseGraph))
         || !basedOn.sourceId().equals(sourceIdOf(candidateGraph))
         || !basedOn.sourceId().equals(input.base().source().sourceId())
         || !basedOn.sourceId().equals(input.candidate().source().sourceId())) {
         diagnostics.add(RenameDiagnostic.error(
            "SIR-RENAME-REQUEST-002", RenameDiagnosticStage.REQUEST, null, "请求基线、源快照与输入图的来源不一致"
         ));
      }

      if (!basedOn.graphCanonicalDigest().equals(baseGraph.canonicalDigest())) {
         diagnostics.add(RenameDiagnostic.error(
            "SIR-RENAME-REQUEST-003", RenameDiagnosticStage.REQUEST, null, "请求基线已陈旧，与输入基线图不一致"
         ));
      }

      if (!basedOn.baseSourceSha256Hex().equals(input.base().source().sha256Hex())) {
         diagnostics.add(RenameDiagnostic.error(
            "SIR-RENAME-REQUEST-004",
            RenameDiagnosticStage.REQUEST,
            null,
            "请求基线的源摘要与输入基线源不一致：源字节已变（即使图未变），请求基线已陈旧"
         ));
      }
   }

   /** A snapshot must describe the graph it is attached to, or the source digest refers to something else. */
   private static void checkSnapshotIdentity(
      RenameRevisionSnapshot snapshot, SymbolId declarationSymbol, String side, List<RenameDiagnostic> diagnostics
   ) {
      if (!snapshot.source().sourceId().equals(sourceIdOf(snapshot.graph()))) {
         diagnostics.add(RenameDiagnostic.error(
            "SIR-RENAME-REQUEST-002",
            RenameDiagnosticStage.REQUEST,
            declarationSymbol,
            side + "源快照与同侧图不是同一来源"
         ));
      }
   }

   private static ClosureComputer.Closure closure(
      ProjectGraph graph, SymbolId declarationSymbol, String side, List<RenameDiagnostic> diagnostics
   ) {
      ClosureComputer.ClosureResult result = ClosureComputer.compute(graph, declarationSymbol);
      if (result instanceof ClosureComputer.ClosureResult.Success success) {
         return success.closure();
      }

      ClosureComputer.ClosureResult.MissingTrace missing = (ClosureComputer.ClosureResult.MissingTrace) result;
      diagnostics.add(RenameDiagnostic.error(
         "SIR-RENAME-MAP-002", RenameDiagnosticStage.MAPPING, declarationSymbol, side + "闭包不完整: " + missing.detail()
      ));
      return null;
   }

   private static void checkArtifactMapping(
      ClosureComputer.Closure baseClosure,
      ClosureComputer.Closure candidateClosure,
      SymbolId declarationSymbol,
      List<RenameDiagnostic> diagnostics
   ) {
      List<ProjectGraphNode.Artifact> baseArtifacts = baseClosure.artifacts();
      List<ProjectGraphNode.Artifact> candidateArtifacts = candidateClosure.artifacts();
      if (baseArtifacts.size() != candidateArtifacts.size()) {
         diagnostics.add(RenameDiagnostic.error(
            "SIR-RENAME-MAP-001",
            RenameDiagnosticStage.MAPPING,
            declarationSymbol,
            "旧/新受管 artifact 数量不一致: " + baseArtifacts.size() + " != " + candidateArtifacts.size()
         ));
         return;
      }

      Map<LoweredNodeId, ProjectGraphNode.Artifact> candidateById = new LinkedHashMap<>();
      for (ProjectGraphNode.Artifact artifact : candidateArtifacts) {
         candidateById.put(artifact.artifactId(), artifact);
      }

      for (ProjectGraphNode.Artifact baseArtifact : baseArtifacts) {
         ProjectGraphNode.Artifact candidateArtifact = candidateById.get(baseArtifact.artifactId());
         if (candidateArtifact == null) {
            diagnostics.add(RenameDiagnostic.error(
               "SIR-RENAME-MAP-002",
               RenameDiagnosticStage.MAPPING,
               declarationSymbol,
               "候选缺少同一受管 artifact: " + baseArtifact.artifactId().value()
            ));
            continue;
         }

         if (!candidateArtifact.role().equals(baseArtifact.role())
            || !candidateArtifact.ownerSymbol().equals(baseArtifact.ownerSymbol())) {
            diagnostics.add(RenameDiagnostic.error(
               "SIR-RENAME-MAP-002",
               RenameDiagnosticStage.MAPPING,
               declarationSymbol,
               "受管 artifact 的角色或归属在两侧不一致: " + baseArtifact.artifactId().value()
            ));
         }
      }
   }

   /**
    * Defensive: the three sets are built as a partition of the two closures, so they cannot overlap.
    * The authority for the invariant is {@link RenamePlan}'s constructor, which refuses an
    * overlapping plan; this check keeps the planner's contract explicit for any future input path.
    */
   private static void checkExclusiveSets(
      List<RenameFileUpdate> updates,
      List<RenameFileWithdrawal> withdrawals,
      List<RenameFileEstablishment> establishments,
      SymbolId declarationSymbol,
      List<RenameDiagnostic> diagnostics
   ) {
      Set<String> seen = new LinkedHashSet<>();
      List<String> paths = new ArrayList<>();
      updates.forEach(value -> paths.add(value.relativePath()));
      withdrawals.forEach(value -> paths.add(value.relativePath()));
      establishments.forEach(value -> paths.add(value.relativePath()));
      for (String path : paths) {
         if (!seen.add(path)) {
            diagnostics.add(RenameDiagnostic.errorForPath(
               "SIR-RENAME-PATH-003",
               RenameDiagnosticStage.PATH,
               declarationSymbol,
               path,
               "更新/撤销/建立三个集合必须互斥"
            ));
         }
      }
   }

   /**
    * How one managed path differs between the two revisions. A rename's file sets must be exactly the
    * changes of the two projects: the closure of the renamed declaration alone is not enough, because
    * a rename of a declaration's name can move files the closure does not own (an input DTO named
    * after the capability, a second declaration, a project file). Anything the plan cannot explain is
    * refused rather than left for an apply to trip over.
    */
   private enum FileChange {
      UNCHANGED("内容未变"),
      UPDATE("内容替换"),
      WITHDRAWAL("撤销"),
      ESTABLISHMENT("建立");

      private final String label;

      FileChange(String label) {
         this.label = label;
      }

      String label() {
         return this.label;
      }
   }

   /**
    * Fail closed unless the three sets are exactly the managed-file diff of the two revisions:
    * every path that appeared, disappeared or changed content is named by the plan with the kind and
    * digests those revisions actually have, and no unchanged path is named at all.
    */
   private static void checkManagedFileCoverage(
      List<RenameFileUpdate> updates,
      List<RenameFileWithdrawal> withdrawals,
      List<RenameFileEstablishment> establishments,
      ProjectGraph baseGraph,
      ProjectGraph candidateGraph,
      SymbolId declarationSymbol,
      List<RenameDiagnostic> diagnostics
   ) {
      Map<String, ProjectGraphNode.ProjectFile> baseManifest = indexAllFiles(baseGraph);
      Map<String, ProjectGraphNode.ProjectFile> candidateManifest = indexAllFiles(candidateGraph);
      Map<String, RenameFileUpdate> updatesByPath = new LinkedHashMap<>();
      updates.forEach(update -> updatesByPath.put(update.relativePath(), update));
      Map<String, RenameFileWithdrawal> withdrawalsByPath = new LinkedHashMap<>();
      withdrawals.forEach(withdrawal -> withdrawalsByPath.put(withdrawal.relativePath(), withdrawal));
      Map<String, RenameFileEstablishment> establishmentsByPath = new LinkedHashMap<>();
      establishments.forEach(establishment -> establishmentsByPath.put(establishment.relativePath(), establishment));

      Set<String> paths = new TreeSet<>(baseManifest.keySet());
      paths.addAll(candidateManifest.keySet());
      paths.addAll(updatesByPath.keySet());
      paths.addAll(withdrawalsByPath.keySet());
      paths.addAll(establishmentsByPath.keySet());

      for (String path : paths) {
         ProjectGraphNode.ProjectFile baseFile = baseManifest.get(path);
         ProjectGraphNode.ProjectFile candidateFile = candidateManifest.get(path);
         FileChange expected = changeOf(baseFile, candidateFile);
         RenameFileUpdate update = updatesByPath.get(path);
         RenameFileWithdrawal withdrawal = withdrawalsByPath.get(path);
         RenameFileEstablishment establishment = establishmentsByPath.get(path);
         FileChange planned = update != null
            ? FileChange.UPDATE
            : withdrawal != null ? FileChange.WITHDRAWAL : establishment != null ? FileChange.ESTABLISHMENT : null;

         if (expected == FileChange.UNCHANGED) {
            if (planned != null) {
               diagnostics.add(RenameDiagnostic.errorForPath(
                  "SIR-RENAME-PATH-004",
                  RenameDiagnosticStage.PATH,
                  declarationSymbol,
                  path,
                  "计划命名了两次修订间内容未变的路径（或该路径不在任一受管清单中）：计划称其为" + planned.label()
               ));
            }

            continue;
         }

         if (planned != expected) {
            diagnostics.add(RenameDiagnostic.errorForPath(
               "SIR-RENAME-PATH-004",
               RenameDiagnosticStage.PATH,
               declarationSymbol,
               path,
               planned == null
                  ? "受管文件在候选修订中" + expected.label() + "，但计划完全没有覆盖该路径"
                  : "受管文件在候选修订中" + expected.label() + "，计划却把它当作" + planned.label()
            ));
            continue;
         }

         boolean matches = switch (expected) {
            case UPDATE -> update.baseByteCount() == baseFile.provenance().byteCount()
               && update.baseSha256Hex().equals(baseFile.provenance().sha256Hex())
               && update.candidateByteCount() == candidateFile.provenance().byteCount()
               && update.candidateSha256Hex().equals(candidateFile.provenance().sha256Hex());
            case WITHDRAWAL -> withdrawal.byteCount() == baseFile.provenance().byteCount()
               && withdrawal.sha256Hex().equals(baseFile.provenance().sha256Hex());
            case ESTABLISHMENT -> establishment.byteCount() == candidateFile.provenance().byteCount()
               && establishment.sha256Hex().equals(candidateFile.provenance().sha256Hex());
            case UNCHANGED -> true;
         };
         if (!matches) {
            diagnostics.add(RenameDiagnostic.errorForPath(
               "SIR-RENAME-PATH-004",
               RenameDiagnosticStage.PATH,
               declarationSymbol,
               path,
               "计划记录的" + expected.label() + "文件长度或摘要与当前修订的受管清单不一致"
            ));
         }
      }
   }

   private static FileChange changeOf(ProjectGraphNode.ProjectFile baseFile, ProjectGraphNode.ProjectFile candidateFile) {
      if (baseFile == null) {
         return candidateFile == null ? FileChange.UNCHANGED : FileChange.ESTABLISHMENT;
      }

      if (candidateFile == null) {
         return FileChange.WITHDRAWAL;
      }

      return baseFile.provenance().sha256Hex().equals(candidateFile.provenance().sha256Hex())
         ? FileChange.UNCHANGED
         : FileChange.UPDATE;
   }

   private static void checkBaseFile(
      ProjectGraphNode.ProjectFile file,
      String path,
      long byteCount,
      String sha256Hex,
      SymbolId declarationSymbol,
      List<RenameDiagnostic> diagnostics
   ) {
      if (file == null) {
         diagnostics.add(RenameDiagnostic.errorForPath(
            "SIR-RENAME-STALE-001",
            RenameDiagnosticStage.STALE,
            declarationSymbol,
            path,
            "基线受管清单中已没有计划依赖的路径"
         ));
         return;
      }

      if (file.provenance().byteCount() != byteCount || !file.provenance().sha256Hex().equals(sha256Hex)) {
         diagnostics.add(RenameDiagnostic.errorForPath(
            "SIR-RENAME-STALE-001",
            RenameDiagnosticStage.STALE,
            declarationSymbol,
            path,
            "基线文件的长度或摘要与计划不一致"
         ));
      }
   }

   private static RenameFileUpdate update(ProjectGraphNode.ProjectFile baseFile, ProjectGraphNode.ProjectFile candidateFile) {
      return new RenameFileUpdate(
         candidateFile.id().relativePath(),
         candidateFile.provenance().artifactId(),
         candidateFile.provenance().ownerSymbol(),
         baseFile.provenance().byteCount(),
         baseFile.provenance().sha256Hex(),
         candidateFile.provenance().byteCount(),
         candidateFile.provenance().sha256Hex()
      );
   }

   private static RenameFileWithdrawal withdrawal(ProjectGraphNode.ProjectFile baseFile) {
      return new RenameFileWithdrawal(
         baseFile.id().relativePath(),
         baseFile.provenance().artifactId(),
         baseFile.provenance().ownerSymbol(),
         baseFile.provenance().byteCount(),
         baseFile.provenance().sha256Hex()
      );
   }

   private static RenameFileEstablishment establishment(ProjectGraphNode.ProjectFile candidateFile) {
      return new RenameFileEstablishment(
         candidateFile.id().relativePath(),
         candidateFile.provenance().artifactId(),
         candidateFile.provenance().ownerSymbol(),
         candidateFile.provenance().byteCount(),
         candidateFile.provenance().sha256Hex()
      );
   }

   private static Map<String, ProjectGraphNode.ProjectFile> indexFiles(ClosureComputer.Closure closure) {
      Map<String, ProjectGraphNode.ProjectFile> files = new LinkedHashMap<>();
      for (ProjectGraphNode.ProjectFile file : closure.files()) {
         files.put(file.id().relativePath(), file);
      }

      return files;
   }

   private static Map<String, ProjectGraphNode.ProjectFile> indexAllFiles(ProjectGraph graph) {
      Map<String, ProjectGraphNode.ProjectFile> files = new LinkedHashMap<>();
      for (ProjectGraphNode node : graph.nodes()) {
         if (node instanceof ProjectGraphNode.ProjectFile file) {
            files.put(file.id().relativePath(), file);
         }
      }

      return files;
   }

   /**
    * The declaration a declared identity belongs to: a capability, or a named member of an entity.
    * Entity members are not top-level declarations, so they are looked up inside their entity.
    */
   private record NamedDeclaration(String name, RenameSubjectKind kind) {
   }

   private static NamedDeclaration findDeclaration(NormalizedSemanticModel model, SymbolId declarationSymbol) {
      for (NormalizedDeclaration declaration : model.declarations()) {
         if (declaration instanceof NormalizedCapability capability && capability.id().equals(declarationSymbol)) {
            return new NamedDeclaration(capability.name(), RenameSubjectKind.CAPABILITY);
         }

         if (declaration instanceof NormalizedEntity entity) {
            for (NormalizedField field : entity.fields()) {
               if (field.id().equals(declarationSymbol)) {
                  return new NamedDeclaration(field.name(), RenameSubjectKind.ENTITY_FIELD);
               }
            }
         }
      }

      return null;
   }

   private static SourceId sourceIdOf(ProjectGraph graph) {
      for (ProjectGraphNode node : graph.nodes()) {
         if (node instanceof ProjectGraphNode.Project project) {
            return project.provenance().sourceId();
         }
      }

      throw new IllegalStateException("ProjectGraph has no Project root node");
   }

   private static boolean hasError(List<RenameDiagnostic> diagnostics) {
      return diagnostics.stream().anyMatch(RenameDiagnostic::isError);
   }

   private static RenameAnalysis rejection(List<RenameDiagnostic> diagnostics) {
      if (!hasError(diagnostics)) {
         diagnostics.add(RenameDiagnostic.error(
            "SIR-RENAME-REQUEST-001", RenameDiagnosticStage.REQUEST, null, "planner rejected without a specific diagnostic"
         ));
      }

      return new RenameAnalysis.Rejected(sortAndCopy(diagnostics));
   }

   private static List<RenameDiagnostic> sortAndCopy(List<RenameDiagnostic> diagnostics) {
      List<RenameDiagnostic> sorted = new ArrayList<>(diagnostics);
      sorted.sort(Comparator.<RenameDiagnostic, String>comparing(diagnostic -> diagnostic.stage().name())
         .thenComparing(diagnostic -> diagnostic.declarationSymbol().map(SymbolId::value).orElse(""))
         .thenComparing(diagnostic -> diagnostic.relativePath().orElse(""))
         .thenComparing(RenameDiagnostic::code));
      return List.copyOf(sorted);
   }
}

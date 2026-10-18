package io.kcg.sir.change.edit;

import io.kcg.sir.api.Diagnostic;
import io.kcg.sir.api.SirParser;
import io.kcg.sir.api.SirSource;
import io.kcg.sir.ast.AstSourceUnit;
import io.kcg.sir.change.edit.EditResult.LocatedChange;
import io.kcg.sir.change.edit.EditResult.Reason;
import io.kcg.sir.change.edit.EditResult.SourceChange;
import io.kcg.sir.internal.CommentScanner;
import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import io.kcg.sir.semantic.api.ProjectSemanticInput;
import io.kcg.sir.semantic.api.SemanticAnalysis;
import io.kcg.sir.semantic.api.SirSemanticAnalyzer;
import io.kcg.sir.semantic.model.NormalizedDeclaration;
import io.kcg.sir.semantic.schema.DraftOrder;
import io.kcg.sir.semantic.schema.SlotKind;
import io.kcg.sir.semantic.schema.SoftwareDraft;
import io.kcg.sir.semantic.schema.SoftwareSchema;
import io.kcg.sir.semantic.schema.StructNode;
import io.kcg.sir.semantic.schema.StructValue;
import io.kcg.sir.semantic.source.SirDeclarationWriter;
import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourceSnapshot;
import io.kcg.sir.source.SourceSpan;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Edits a set of SIR sources - a software written in one file is a set with one member - with a batch of
 * add, delete and update commands.
 *
 * <p>The edit never works on text. It reads the source with the formal analysis, takes the
 * software as a draft, applies the commands to the draft, writes <em>only the declarations that
 * changed</em> back into the original text (everything else stays byte for byte), and then runs the
 * formal analysis again on the written text. The result is returned only if that fresh analysis
 * succeeds and describes exactly the draft the commands produced. Names are bound once, in that last
 * analysis; the edit never reuses a binding from the old source.
 *
 * <p>It works on declarations. Each changed declaration is rewritten inside the file it is in, and new
 * declarations are appended after the last declaration that stays in the entry file (names they use from
 * other files must already be imported there; the fresh analysis refuses the result otherwise). It refuses, with a reason, what it cannot do safely: editing the header or
 * the imports, a source with a byte order mark, and any rewrite that would remove a comment.
 */
public final class SourceEditor {
   private static final String DECLARATIONS = SoftwareDraft.DECLARATIONS;

   private final SirParser parser = SirParser.create();
   private final SirSemanticAnalyzer analyzer = new SirSemanticAnalyzer();
   private final SoftwareSchema schema = SoftwareSchema.normalizedModel();
   private final DraftEdit draftEdit = new DraftEdit(this.schema);

   /** The digest a batch must carry to be applied to these exact sources (the digest of the source set). */
   public static String sha256(SourceSnapshot sources) {
      return sources.sha256Hex();
   }

   /** The batch must carry {@link #sha256(SourceSnapshot)} of {@code sources}. */
   public EditResult edit(SourceSnapshot sources, CommandBatch batch) {
      Map<SourceId, String> files = new LinkedHashMap<>();
      for (var entry : sources.manifest().files()) {
         String text = decode(sources.bytes(entry.sourceId()));
         if (text == null) {
            return new EditResult.Refused(Reason.UNSUPPORTED_SOURCE, "source '" + entry.sourceId().value() + "' is not valid UTF-8", List.of(), Optional.empty());
         }

         files.put(entry.sourceId(), text);
      }

      Outcome outcome = this.run(new Documents(sources.entry(), files), batch, sha256(sources));
      if (outcome instanceof Failed failed) {
         return new EditResult.Refused(failed.reason(), failed.message(), failed.details(), Optional.ofNullable(failed.candidate()).map(c -> snapshotOf(sources.entry(), c)));
      }

      Done done = (Done)outcome;
      return new EditResult.Edited(snapshotOf(sources.entry(), done.files()), done.delta(), done.changes());
   }

   /**
    * What declarations written as text would add to these sources.
    *
    * <p>The text is placed after the last declaration of the entry source - where {@link #edit} puts a new
    * declaration - and the result goes through the same formal analysis as any edit, so a name that is
    * already taken, a reference to something that is not there, or a rule the declarations break all
    * come back as the analysis' own findings. Only the declarations that were not already there are
    * returned. Nothing is written: the caller sends them as {@code add} commands, and the edit then
    * checks them once more against the digest returned here.
    */
   public ScaffoldResult scaffold(SourceSnapshot sources, String declarationsText) {
      Map<SourceId, String> files = new LinkedHashMap<>();
      for (var entry : sources.manifest().files()) {
         String text = decode(sources.bytes(entry.sourceId()));
         if (text == null) {
            return new ScaffoldResult.Refused("source '" + entry.sourceId().value() + "' is not valid UTF-8", List.of());
         }

         if (text.startsWith("﻿")) {
            return new ScaffoldResult.Refused("a source with a leading byte order mark is not supported yet", List.of());
         }

         files.put(entry.sourceId(), text);
      }

      Documents documents = new Documents(sources.entry(), files);
      Analyzed baseline = this.analyze(documents);
      if (baseline.model == null) {
         return new ScaffoldResult.Refused("the source to add to is not a valid software", baseline.problems);
      }

      String entryText = files.get(sources.entry());
      var parsed = this.parser.parseProject(new SirSource(sources.entry(), entryText));
      List<io.kcg.sir.ast.AstDeclaration> declared = parsed.unit().orElseThrow() instanceof AstSourceUnit.Root root
         ? root.document().software().declarations()
         : List.of();
      if (declared.isEmpty()) {
         return new ScaffoldResult.Refused("the entry source declares nothing yet, so there is nothing to attach the declarations to", List.of());
      }

      int[] points = entryText.codePoints().toArray();
      int insertAt = declared.stream().mapToInt(d -> d.span().end().codePointOffset()).max().orElseThrow();
      String extended = new String(points, 0, insertAt) + "\n" + declarationsText.stripTrailing() + new String(points, insertAt, points.length - insertAt);
      Map<SourceId, String> candidateFiles = new LinkedHashMap<>(files);
      candidateFiles.put(sources.entry(), extended);
      Analyzed candidate = this.analyze(new Documents(sources.entry(), candidateFiles));
      if (candidate.model == null) {
         return new ScaffoldResult.Refused("the declarations do not fit this software", candidate.problems);
      }

      Set<String> present = new java.util.HashSet<>();
      for (StructNode node : SoftwareDraft.of(baseline.model).declarations()) {
         present.add(SoftwareDraft.identityOf(node).orElseThrow());
      }

      Set<String> written = new java.util.HashSet<>();
      candidate.model.declarations().stream().filter(candidate.model::isWritten).forEach(d -> written.add(d.id().value()));
      List<StructNode> added = SoftwareDraft.of(candidate.model).declarations().stream()
         .filter(node -> written.contains(SoftwareDraft.identityOf(node).orElseThrow()))
         .filter(node -> !present.contains(SoftwareDraft.identityOf(node).orElseThrow()))
         .map(SourceEditor::authored)
         .toList();
      return new ScaffoldResult.Scaffolded(baseline.model.softwareName(), sha256(sources), added);
   }

   /** The part of a node a command may supply: its authored slots only, all the way down (identities and computed slots are the editor's own). */
   private static StructNode authored(StructNode node) {
      List<StructNode.Slot> slots = new ArrayList<>();
      for (StructNode.Slot slot : node.slots()) {
         if (slot.kind() == SlotKind.FACT || slot.kind() == SlotKind.REFERENCE) {
            slots.add(new StructNode.Slot(slot.key(), slot.kind(), authored(slot.value())));
         }
      }

      return new StructNode(node.kind(), slots);
   }

   private static StructValue authored(StructValue value) {
      return switch (value) {
         case StructValue.Child child -> new StructValue.Child(authored(child.node()));
         case StructValue.Seq seq -> new StructValue.Seq(seq.elements().stream().map(SourceEditor::authored).toList());
         default -> value;
      };
   }

   private static SourceSnapshot snapshotOf(SourceId entry, Map<SourceId, String> files) {
      Map<SourceId, byte[]> bytes = new LinkedHashMap<>();
      files.forEach((id, text) -> bytes.put(id, text.getBytes(StandardCharsets.UTF_8)));
      return new SourceSnapshot(entry, bytes);
   }

   private static String decode(byte[] bytes) {
      try {
         return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
      } catch (CharacterCodingException e) {
         return null;
      }
   }

   /** The texts being edited, by source. */
   private record Documents(SourceId entry, Map<SourceId, String> files) {
   }

   private sealed interface Outcome permits Done, Failed {
   }

   private record Done(Map<SourceId, String> files, SemanticDelta delta, List<LocatedChange> changes) implements Outcome {
   }

   private record Failed(Reason reason, String message, List<String> details, Map<SourceId, String> candidate) implements Outcome {
   }

   private Outcome run(Documents documents, CommandBatch batch, String baselineSha) {
      if (!batch.protocol().equals(CommandBatch.PROTOCOL)) {
         return refuse(Reason.UNSUPPORTED_BATCH, "unknown command protocol '" + batch.protocol() + "'");
      }

      for (String text : documents.files().values()) {
         if (text.startsWith("﻿")) {
            return refuse(Reason.UNSUPPORTED_SOURCE, "a source with a leading byte order mark is not supported yet");
         }
      }

      if (!baselineSha.equals(batch.baselineSha256())) {
         return refuse(Reason.STALE_BASELINE, "the batch was written against a different source text");
      }

      Analyzed baseline = this.analyze(documents);
      if (baseline.model == null) {
         return new Failed(Reason.BASELINE_INVALID, "the source to edit is not a valid software", baseline.problems, null);
      }

      NormalizedSemanticModel model = baseline.model;
      if (!model.softwareName().equals(batch.softwareName())) {
         return refuse(Reason.STALE_BASELINE, "the batch is for software '" + batch.softwareName() + "' but the source is '" + model.softwareName() + "'");
      }

      SoftwareDraft before = SoftwareDraft.of(model);
      DraftEdit.Result applied = this.draftEdit.apply(before, batch.commands());
      if (applied instanceof DraftEdit.Rejected rejected) {
         return refuse(Reason.COMMAND_REJECTED, "command " + (rejected.commandIndex() + 1) + " of " + batch.commands().size() + ": " + rejected.reason());
      }

      SoftwareDraft edited = ((DraftEdit.Applied)applied).draft();
      if (!outsideDeclarations(edited).equals(outsideDeclarations(before))) {
         return refuse(Reason.NOT_SUPPORTED_YET, "editing the software name, metadata or target is not supported yet; only declarations can be edited");
      }

      Plan plan = this.plan(model, before, edited);
      if (plan.refusal != null) {
         return plan.refusal;
      }

      return this.write(documents, model, before, edited, plan);
   }

   // ----- what to change -----------------------------------------------------------------------

   private record Plan(List<NormalizedDeclaration> removed, Map<NormalizedDeclaration, StructNode> changed, List<StructNode> added, Failed refusal) {
   }

   private Plan plan(NormalizedSemanticModel model, SoftwareDraft before, SoftwareDraft edited) {
      Map<String, NormalizedDeclaration> baseline = new LinkedHashMap<>();
      for (NormalizedDeclaration declaration : model.declarations()) {
         baseline.put(declaration.id().value(), declaration);
      }

      Map<String, StructNode> baselineNodes = new LinkedHashMap<>();
      for (StructNode node : before.declarations()) {
         baselineNodes.put(SoftwareDraft.identityOf(node).orElseThrow(), node);
      }

      Map<NormalizedDeclaration, StructNode> changed = new LinkedHashMap<>();
      List<StructNode> added = new ArrayList<>();
      Set<String> kept = new java.util.HashSet<>();
      for (StructNode node : edited.declarations()) {
         Optional<String> identity = SoftwareDraft.identityOf(node);
         if (identity.isEmpty()) {
            added.add(node);
         } else if (!baseline.containsKey(identity.get())) {
            return new Plan(List.of(), Map.of(), List.of(), new Failed(Reason.NOT_SUPPORTED_YET, "a new declaration cannot carry an identity: " + identity.get(), List.of(), null));
         } else if (!kept.add(identity.get())) {
            return new Plan(List.of(), Map.of(), List.of(), new Failed(Reason.COMMAND_REJECTED, "two declarations share the identity " + identity.get(), List.of(), null));
         } else if (!node.equals(baselineNodes.get(identity.get()))) {
            changed.put(baseline.get(identity.get()), node);
         }
      }

      List<NormalizedDeclaration> removed = baseline.entrySet().stream().filter(e -> !kept.contains(e.getKey())).map(Map.Entry::getValue).toList();
      // What a use declaration expanded into is not source text: it changes only through the use declaration.
      for (NormalizedDeclaration declaration : changed.keySet()) {
         if (!model.isWritten(declaration)) {
            return new Plan(List.of(), Map.of(), List.of(), expandedRefusal(model, declaration));
         }
      }

      Set<String> removedIds = new java.util.HashSet<>();
      removed.forEach(d -> removedIds.add(d.id().value()));
      for (NormalizedDeclaration declaration : removed) {
         var origin = model.expandedFrom(declaration.id());
         if (origin.isPresent() && !removedIds.contains(origin.get().feature().value())) {
            return new Plan(List.of(), Map.of(), List.of(), expandedRefusal(model, declaration));
         }
      }

      return new Plan(removed.stream().filter(model::isWritten).toList(), changed, added, null);
   }

   private static Failed expandedRefusal(NormalizedSemanticModel model, NormalizedDeclaration declaration) {
      var feature = model.expandedFrom(declaration.id()).orElseThrow().feature();
      String use = model.declarations().stream().filter(d -> d.id().equals(feature)).map(NormalizedDeclaration::name).findFirst()
         .orElse(feature.value());
      return new Failed(Reason.COMMAND_REJECTED, "'" + declaration.name() + "' is expanded from 'use " + use
         + "'; change the arguments of the use declaration instead, or detach it with kcg scaffold", List.of(), null);
   }

   // ----- writing it back ----------------------------------------------------------------------

   /** One edit of the original text, in code points. */
   private record Splice(int start, int end, String replacement) {
   }

   private Outcome write(Documents documents, NormalizedSemanticModel model, SoftwareDraft before, SoftwareDraft edited, Plan plan) {
      Map<SourceId, int[]> texts = new LinkedHashMap<>();
      documents.files().forEach((id, text) -> texts.put(id, text.codePoints().toArray()));
      // The types the edited software has, so a field is written without repeating what its type already brings.
      Map<String, SirDeclarationWriter.Brought> types = SirDeclarationWriter.typesOf(edited.declarations());

      Failed comment = this.commentGuard(documents, plan);
      if (comment != null) {
         return comment;
      }

      Map<SourceId, List<Splice>> splices = new LinkedHashMap<>();
      List<LocatedChange> report = new ArrayList<>();
      try {
         for (Map.Entry<NormalizedDeclaration, StructNode> entry : plan.changed.entrySet()) {
            SourceId id = sourceOf(documents, entry.getKey());
            int[] text = texts.get(id);
            String newline = newlineOf(documents.files().get(id));
            SourceSpan span = entry.getKey().span();
            int start = span.start().codePointOffset();
            int end = span.end().codePointOffset();
            String written = new SirDeclarationWriter(indentOf(text, start).orElse(""), types).write(entry.getValue()).replace("\n", newline);
            splices.computeIfAbsent(id, k -> new ArrayList<>()).add(new Splice(start, end, written));
            report.add(new LocatedChange(id, new SourceChange(SourceChange.Type.REWRITE, nameOfDeclaration(entry.getValue()), slice(text, start, end), written)));
         }
      } catch (IllegalArgumentException e) {
         return refuse(Reason.NOT_REPRESENTABLE, e.getMessage());
      }

      for (NormalizedDeclaration declaration : plan.removed) {
         SourceId id = sourceOf(documents, declaration);
         int[] text = texts.get(id);
         int start = declaration.span().start().codePointOffset();
         int end = declaration.span().end().codePointOffset();
         int[] range = wholeLines(text, start, end);
         splices.computeIfAbsent(id, k -> new ArrayList<>()).add(new Splice(range[0], range[1], ""));
         report.add(new LocatedChange(id, new SourceChange(SourceChange.Type.REMOVE, declaration.name(), slice(text, start, end), "")));
      }

      if (!plan.added.isEmpty()) {
         Optional<NormalizedDeclaration> anchor = anchorFor(documents, model, plan);
         if (anchor.isEmpty()) {
            return refuse(Reason.NOT_SUPPORTED_YET, "adding declarations needs at least one declaration that stays; none is left in the file");
         }

         SourceId id = sourceOf(documents, anchor.get());
         int[] text = texts.get(id);
         String newline = newlineOf(documents.files().get(id));
         int anchorStart = anchor.get().span().start().codePointOffset();
         int anchorEnd = anchor.get().span().end().codePointOffset();
         String indent = indentOf(text, anchorStart).orElse("    ");
         String gap = blankLineBetweenDeclarations(documents, model, id, text) ? newline + newline : newline;
         StringBuilder inserted = new StringBuilder();
         try {
            for (StructNode node : plan.added) {
               String written = new SirDeclarationWriter(indent, types).write(node).replace("\n", newline);
               inserted.append(gap).append(indent).append(written);
               report.add(new LocatedChange(id, new SourceChange(SourceChange.Type.INSERT, nameOfDeclaration(node), "", written)));
            }
         } catch (IllegalArgumentException e) {
            return refuse(Reason.NOT_REPRESENTABLE, e.getMessage());
         }

         // one insertion after the last declaration that stays carries every added declaration, in draft order
         splices.computeIfAbsent(id, k -> new ArrayList<>()).add(new Splice(anchorEnd, anchorEnd, inserted.toString()));
      }

      for (List<Splice> inFile : splices.values()) {
         Failed overlap = overlapOf(inFile);
         if (overlap != null) {
            return overlap;
         }
      }

      Map<SourceId, String> candidate = new LinkedHashMap<>(documents.files());
      splices.forEach((id, inFile) -> candidate.put(id, apply(texts.get(id), inFile)));
      Documents result = new Documents(documents.entry(), candidate);
      Analyzed analyzed = this.analyze(result);
      if (analyzed.model == null) {
         return new Failed(Reason.INVALID_RESULT, "the edited software is not valid", analyzed.problems, candidate);
      }

      SoftwareDraft after = SoftwareDraft.of(analyzed.model);
      // Expanded declarations are re-derived from the use declarations, so only what the author writes is compared.
      StructNode expected = DraftOrder.canonical(writtenOnly(edited, model).without(Set.of(SlotKind.IDENTITY)), this.schema);
      StructNode actual = DraftOrder.canonical(writtenOnly(after, analyzed.model).without(Set.of(SlotKind.IDENTITY)), this.schema);
      if (!expected.equals(actual)) {
         SemanticDelta difference = SemanticDelta.between(new SoftwareDraft(expected), new SoftwareDraft(actual), this.schema);
         return new Failed(Reason.ROUND_TRIP_MISMATCH, "the written source does not read back as the edited draft", difference.changes().stream().map(Object::toString).toList(), candidate);
      }

      report.sort(Comparator.comparing((LocatedChange c) -> c.change().type().ordinal()).thenComparing(c -> c.change().declaration()));
      return new Done(candidate, SemanticDelta.between(before, after, this.schema), report);
   }

   /** The draft's root with the declarations {@code model} says a use declaration expanded into left out. */
   private static StructNode writtenOnly(SoftwareDraft draft, NormalizedSemanticModel model) {
      Set<String> expanded = new java.util.HashSet<>();
      model.expansions().keySet().forEach(id -> expanded.add(id.value()));
      List<StructNode.Slot> slots = new ArrayList<>();
      for (StructNode.Slot slot : draft.root().slots()) {
         if (slot.key().equals(SoftwareDraft.DECLARATIONS) && slot.value() instanceof StructValue.Seq seq) {
            slots.add(new StructNode.Slot(slot.key(), slot.kind(), new StructValue.Seq(seq.elements().stream()
               .filter(e -> !(e instanceof StructValue.Child child) || SoftwareDraft.identityOf(child.node()).map(i -> !expanded.contains(i)).orElse(true))
               .toList())));
         } else {
            slots.add(slot);
         }
      }

      return new StructNode(draft.root().kind(), slots);
   }

   /** The source file a declaration was read from. */
   private static SourceId sourceOf(Documents documents, NormalizedDeclaration declaration) {
      return declaration.span().source();
   }

   private static String newlineOf(String text) {
      return text.contains("\r\n") ? "\r\n" : "\n";
   }

   /**
    * The declaration after which new ones are inserted: the last one that stays in the entry file or,
    * when the entry file has none, in the first other file that has one.
    */
   private static Optional<NormalizedDeclaration> anchorFor(Documents documents, NormalizedSemanticModel model, Plan plan) {
      List<NormalizedDeclaration> staying = model.declarations().stream().filter(model::isWritten)
         .filter(d -> plan.removed.stream().noneMatch(r -> r.id().equals(d.id()))).toList();
      Optional<NormalizedDeclaration> inEntry = staying.stream().filter(d -> sourceOf(documents, d).equals(documents.entry()))
         .max(Comparator.comparingInt(d -> d.span().end().codePointOffset()));
      if (inEntry.isPresent()) {
         return inEntry;
      }

      Optional<SourceId> other = staying.stream().map(d -> sourceOf(documents, d)).min(Comparator.comparing(SourceId::value));
      return other.flatMap(id -> staying.stream().filter(d -> sourceOf(documents, d).equals(id)).max(Comparator.comparingInt(d -> d.span().end().codePointOffset())));
   }

   // ----- comments -----------------------------------------------------------------------------

   private Failed commentGuard(Documents documents, Plan plan) {
      Map<SourceId, List<SourceSpan>> comments = new LinkedHashMap<>();
      documents.files().forEach((id, text) -> comments.put(id, CommentScanner.scan(new SirSource(id, text))));
      List<String> found = new ArrayList<>();
      List<NormalizedDeclaration> touched = new ArrayList<>(plan.changed.keySet());
      touched.addAll(plan.removed);
      for (NormalizedDeclaration declaration : touched) {
         SourceId id = sourceOf(documents, declaration);
         int start = declaration.span().start().codePointOffset();
         int end = declaration.span().end().codePointOffset();
         for (SourceSpan comment : comments.get(id)) {
            if (comment.start().codePointOffset() >= start && comment.end().codePointOffset() <= end) {
               found.add(id.value() + " line " + comment.start().line() + ", inside '" + declaration.name() + "'");
            }
         }
      }

      if (found.isEmpty()) {
         return null;
      }

      return new Failed(Reason.PROTECTED_COMMENT, "the edit would remove a comment; comments are protected", found, null);
   }

   // ----- text helpers -------------------------------------------------------------------------

   private Analyzed analyze(Documents documents) {
      AstSourceUnit.Root root = null;
      List<AstSourceUnit.Fragment> fragments = new ArrayList<>();
      List<String> problems = new ArrayList<>();
      for (Map.Entry<SourceId, String> file : documents.files().entrySet()) {
         SirSource source = new SirSource(file.getKey(), file.getValue());
         var parsed = file.getKey().equals(documents.entry()) ? this.parser.parseProject(source) : this.parser.parseFragment(source);
         if (!parsed.isSuccess()) {
            problems.addAll(describe(parsed.diagnostics()));
         } else if (parsed.unit().orElseThrow() instanceof AstSourceUnit.Root r) {
            root = r;
         } else {
            fragments.add((AstSourceUnit.Fragment)parsed.unit().orElseThrow());
         }
      }

      if (!problems.isEmpty() || root == null) {
         return new Analyzed(null, problems.isEmpty() ? List.of("the entry source is missing") : problems);
      }

      SemanticAnalysis analysis = this.analyzer.analyzeProject(new ProjectSemanticInput(root, fragments));
      if (!analysis.isSuccess()) {
         return new Analyzed(null, describe(analysis.diagnostics()));
      }

      return new Analyzed(analysis.model().orElseThrow(), List.of());
   }

   private record Analyzed(NormalizedSemanticModel model, List<String> problems) {
   }

   private static List<String> describe(List<Diagnostic> diagnostics) {
      return diagnostics.stream()
         .filter(Diagnostic::isError)
         .map(d -> d.code().value() + " " + d.primarySpan().source().value() + " line " + d.primarySpan().start().line() + ": " + d.message())
         .toList();
   }

   private static StructNode outsideDeclarations(SoftwareDraft draft) {
      return draft.root().withSlot(DECLARATIONS, new StructValue.Seq(List.of()));
   }

   private static String nameOfDeclaration(StructNode node) {
      return SoftwareDraft.nameOf(node);
   }

   private static String slice(int[] text, int start, int end) {
      return new String(text, start, end - start);
   }

   /** The whitespace in front of {@code start} on its line, or empty if something else is there. */
   private static Optional<String> indentOf(int[] text, int start) {
      int lineStart = start;
      while (lineStart > 0 && text[lineStart - 1] != '\n') {
         lineStart--;
      }

      for (int i = lineStart; i < start; i++) {
         if (text[i] != ' ' && text[i] != '\t') {
            return Optional.empty();
         }
      }

      return Optional.of(new String(text, lineStart, start - lineStart));
   }

   /** The range to delete so a removed declaration takes its whole line(s) with it when it is alone on them. */
   private static int[] wholeLines(int[] text, int start, int end) {
      int lineStart = start;
      while (lineStart > 0 && text[lineStart - 1] != '\n') {
         lineStart--;
      }

      boolean aloneBefore = indentOf(text, start).isPresent();
      int after = end;
      while (after < text.length && (text[after] == ' ' || text[after] == '\t' || text[after] == '\r')) {
         after++;
      }

      boolean aloneAfter = after >= text.length || text[after] == '\n';
      if (aloneBefore && aloneAfter) {
         return new int[] {lineStart, after < text.length ? after + 1 : after};
      }

      return new int[] {start, end};
   }

   private static boolean blankLineBetweenDeclarations(Documents documents, NormalizedSemanticModel model, SourceId file, int[] text) {
      List<NormalizedDeclaration> ordered = model.declarations().stream().filter(model::isWritten).filter(d -> sourceOf(documents, d).equals(file))
         .sorted(Comparator.comparingInt(d -> d.span().start().codePointOffset())).toList();
      for (int i = 0; i + 1 < ordered.size(); i++) {
         String between = slice(text, ordered.get(i).span().end().codePointOffset(), ordered.get(i + 1).span().start().codePointOffset());
         if (between.replace("\r", "").matches("(?s)[ \\t]*\\n[ \\t]*\\n[ \\t]*")) {
            return true;
         }
      }

      return false;
   }

   private static Failed overlapOf(List<Splice> splices) {
      List<Splice> ordered = new ArrayList<>(splices);
      ordered.sort(Comparator.comparingInt(Splice::start).thenComparingInt(Splice::end));
      for (int i = 0; i + 1 < ordered.size(); i++) {
         if (ordered.get(i).end > ordered.get(i + 1).start) {
            return new Failed(Reason.NOT_SUPPORTED_YET, "two edited declarations share a line; edit them in separate batches", List.of(), null);
         }
      }

      return null;
   }

   /** Applies the splices from the back of the text so the offsets of the earlier ones stay valid. */
   private static String apply(int[] text, List<Splice> splices) {
      List<Splice> ordered = new ArrayList<>(splices);
      ordered.sort(Comparator.comparingInt(Splice::start).thenComparingInt(Splice::end).reversed());
      String result = new String(text, 0, text.length);
      for (Splice splice : ordered) {
         int[] cps = result.codePoints().toArray();
         result = new String(cps, 0, splice.start) + splice.replacement + new String(cps, splice.end, cps.length - splice.end);
      }

      return result;
   }

   private static Failed refuse(Reason reason, String message) {
      return new Failed(reason, message, List.of(), null);
   }
}

package io.kcg.sir.change.edit;

import io.kcg.sir.api.Diagnostic;
import io.kcg.sir.api.ParseResult;
import io.kcg.sir.api.SirParser;
import io.kcg.sir.api.SirSource;
import io.kcg.sir.change.edit.EditResult.Reason;
import io.kcg.sir.change.edit.EditResult.Refused;
import io.kcg.sir.change.edit.EditResult.SourceChange;
import io.kcg.sir.internal.CommentScanner;
import io.kcg.sir.semantic.api.NormalizedSemanticModel;
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
import io.kcg.sir.source.SourceSpan;
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
 * Edits a single-file SIR source with a batch of add, delete and update commands.
 *
 * <p>The edit never works on text. It reads the source with the formal analysis, takes the
 * software as a draft, applies the commands to the draft, writes <em>only the declarations that
 * changed</em> back into the original text (everything else stays byte for byte), and then runs the
 * formal analysis again on the written text. The result is returned only if that fresh analysis
 * succeeds and describes exactly the draft the commands produced. Names are bound once, in that last
 * analysis; the edit never reuses a binding from the old source.
 *
 * <p>This first version works on declarations of a single 0.1 file. It refuses, with a reason, what
 * it cannot do safely: editing the header, a source with a byte order mark, and any rewrite that
 * would remove a comment.
 */
public final class SourceEditor {
   private static final SourceId SOURCE_ID = SourceId.of("edit-source");
   private static final String DECLARATIONS = SoftwareDraft.DECLARATIONS;

   private final SirParser parser = SirParser.create();
   private final SirSemanticAnalyzer analyzer = new SirSemanticAnalyzer();
   private final SoftwareSchema schema = SoftwareSchema.normalizedModel();
   private final DraftEdit draftEdit = new DraftEdit(this.schema);

   /** The digest a batch must carry to be applied to this exact text. */
   public static String sha256(String text) {
      try {
         return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
      } catch (NoSuchAlgorithmException e) {
         throw new IllegalStateException(e);
      }
   }

   public EditResult edit(String source, CommandBatch batch) {
      if (!batch.protocol().equals(CommandBatch.PROTOCOL)) {
         return refuse(Reason.UNSUPPORTED_BATCH, "unknown command protocol '" + batch.protocol() + "'");
      }

      if (source.startsWith("﻿")) {
         return refuse(Reason.UNSUPPORTED_SOURCE, "a source with a leading byte order mark is not supported yet");
      }

      if (!sha256(source).equals(batch.baselineSha256())) {
         return refuse(Reason.STALE_BASELINE, "the batch was written against a different source text");
      }

      Analyzed baseline = this.analyze(source);
      if (baseline.model == null) {
         return new Refused(Reason.BASELINE_INVALID, "the source to edit is not a valid software", baseline.problems, Optional.empty());
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

      return this.write(source, model, before, edited, plan);
   }

   // ----- what to change -----------------------------------------------------------------------

   private record Plan(List<NormalizedDeclaration> removed, Map<NormalizedDeclaration, StructNode> changed, List<StructNode> added, Refused refusal) {
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
            return new Plan(List.of(), Map.of(), List.of(), new Refused(Reason.NOT_SUPPORTED_YET, "a new declaration cannot carry an identity: " + identity.get(), List.of(), Optional.empty()));
         } else if (!kept.add(identity.get())) {
            return new Plan(List.of(), Map.of(), List.of(), new Refused(Reason.COMMAND_REJECTED, "two declarations share the identity " + identity.get(), List.of(), Optional.empty()));
         } else if (!node.equals(baselineNodes.get(identity.get()))) {
            changed.put(baseline.get(identity.get()), node);
         }
      }

      List<NormalizedDeclaration> removed = baseline.entrySet().stream().filter(e -> !kept.contains(e.getKey())).map(Map.Entry::getValue).toList();
      return new Plan(removed, changed, added, null);
   }

   // ----- writing it back ----------------------------------------------------------------------

   /** One edit of the original text, in code points. */
   private record Splice(int start, int end, String replacement) {
   }

   private EditResult write(String source, NormalizedSemanticModel model, SoftwareDraft before, SoftwareDraft edited, Plan plan) {
      int[] text = source.codePoints().toArray();
      String newline = source.contains("\r\n") ? "\r\n" : "\n";

      Refused comment = this.commentGuard(source, plan);
      if (comment != null) {
         return comment;
      }

      List<Splice> splices = new ArrayList<>();
      List<SourceChange> report = new ArrayList<>();
      try {
         for (Map.Entry<NormalizedDeclaration, StructNode> entry : plan.changed.entrySet()) {
            SourceSpan span = entry.getKey().span();
            int start = span.start().codePointOffset();
            int end = span.end().codePointOffset();
            String written = new SirDeclarationWriter(indentOf(text, start).orElse("")).write(entry.getValue()).replace("\n", newline);
            splices.add(new Splice(start, end, written));
            report.add(new SourceChange(SourceChange.Type.REWRITE, nameOfDeclaration(entry.getValue()), slice(text, start, end), written));
         }
      } catch (IllegalArgumentException e) {
         return refuse(Reason.NOT_REPRESENTABLE, e.getMessage());
      }

      for (NormalizedDeclaration declaration : plan.removed) {
         int start = declaration.span().start().codePointOffset();
         int end = declaration.span().end().codePointOffset();
         int[] range = wholeLines(text, start, end);
         splices.add(new Splice(range[0], range[1], ""));
         report.add(new SourceChange(SourceChange.Type.REMOVE, declaration.name(), slice(text, start, end), ""));
      }

      if (!plan.added.isEmpty()) {
         Optional<NormalizedDeclaration> anchor = model.declarations().stream()
            .filter(d -> plan.removed.stream().noneMatch(r -> r.id().equals(d.id())))
            .max(Comparator.comparingInt(d -> d.span().end().codePointOffset()));
         if (anchor.isEmpty()) {
            return refuse(Reason.NOT_SUPPORTED_YET, "adding declarations needs at least one declaration that stays; none is left in the file");
         }

         int anchorStart = anchor.get().span().start().codePointOffset();
         int anchorEnd = anchor.get().span().end().codePointOffset();
         String indent = indentOf(text, anchorStart).orElse("    ");
         String gap = blankLineBetweenDeclarations(model, text) ? newline + newline : newline;
         StringBuilder inserted = new StringBuilder();
         try {
            for (StructNode node : plan.added) {
               String written = new SirDeclarationWriter(indent).write(node).replace("\n", newline);
               inserted.append(gap).append(indent).append(written);
               report.add(new SourceChange(SourceChange.Type.INSERT, nameOfDeclaration(node), "", written));
            }
         } catch (IllegalArgumentException e) {
            return refuse(Reason.NOT_REPRESENTABLE, e.getMessage());
         }

         // one insertion after the last declaration that stays carries every added declaration, in draft order
         splices.add(new Splice(anchorEnd, anchorEnd, inserted.toString()));
      }

      Refused overlap = overlapOf(splices);
      if (overlap != null) {
         return overlap;
      }

      String candidate = apply(text, splices);
      Analyzed result = this.analyze(candidate);
      if (result.model == null) {
         return new Refused(Reason.INVALID_RESULT, "the edited software is not valid", result.problems, Optional.of(candidate));
      }

      SoftwareDraft after = SoftwareDraft.of(result.model);
      StructNode expected = DraftOrder.canonical(edited.root().without(Set.of(SlotKind.IDENTITY)), this.schema);
      StructNode actual = DraftOrder.canonical(after.root().without(Set.of(SlotKind.IDENTITY)), this.schema);
      if (!expected.equals(actual)) {
         SemanticDelta difference = SemanticDelta.between(new SoftwareDraft(expected), new SoftwareDraft(actual), this.schema);
         return new Refused(Reason.ROUND_TRIP_MISMATCH, "the written source does not read back as the edited draft", difference.changes().stream().map(Object::toString).toList(), Optional.of(candidate));
      }

      report.sort(Comparator.comparing((SourceChange c) -> c.type().ordinal()).thenComparing(SourceChange::declaration));
      return new EditResult.Edited(candidate, SemanticDelta.between(before, after, this.schema), report);
   }

   // ----- comments -----------------------------------------------------------------------------

   private Refused commentGuard(String source, Plan plan) {
      List<SourceSpan> comments = CommentScanner.scan(new SirSource(SOURCE_ID, source));
      List<String> found = new ArrayList<>();
      List<NormalizedDeclaration> touched = new ArrayList<>(plan.changed.keySet());
      touched.addAll(plan.removed);
      for (NormalizedDeclaration declaration : touched) {
         int start = declaration.span().start().codePointOffset();
         int end = declaration.span().end().codePointOffset();
         for (SourceSpan comment : comments) {
            if (comment.start().codePointOffset() >= start && comment.end().codePointOffset() <= end) {
               found.add("line " + comment.start().line() + ", inside '" + declaration.name() + "'");
            }
         }
      }

      if (found.isEmpty()) {
         return null;
      }

      return new Refused(Reason.PROTECTED_COMMENT, "the edit would remove a comment; comments are protected", found, Optional.empty());
   }

   // ----- text helpers -------------------------------------------------------------------------

   private Analyzed analyze(String text) {
      ParseResult parsed = this.parser.parse(new SirSource(SOURCE_ID, text));
      if (!parsed.isSuccess()) {
         return new Analyzed(null, describe(parsed.diagnostics()));
      }

      SemanticAnalysis analysis = this.analyzer.analyze(parsed.document().orElseThrow());
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
         .map(d -> d.code().value() + " line " + d.primarySpan().start().line() + ": " + d.message())
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

   private static boolean blankLineBetweenDeclarations(NormalizedSemanticModel model, int[] text) {
      List<NormalizedDeclaration> ordered = model.declarations().stream().sorted(Comparator.comparingInt(d -> d.span().start().codePointOffset())).toList();
      for (int i = 0; i + 1 < ordered.size(); i++) {
         String between = slice(text, ordered.get(i).span().end().codePointOffset(), ordered.get(i + 1).span().start().codePointOffset());
         if (between.replace("\r", "").matches("(?s)[ \\t]*\\n[ \\t]*\\n[ \\t]*")) {
            return true;
         }
      }

      return false;
   }

   private static Refused overlapOf(List<Splice> splices) {
      List<Splice> ordered = new ArrayList<>(splices);
      ordered.sort(Comparator.comparingInt(Splice::start).thenComparingInt(Splice::end));
      for (int i = 0; i + 1 < ordered.size(); i++) {
         if (ordered.get(i).end > ordered.get(i + 1).start) {
            return new Refused(Reason.NOT_SUPPORTED_YET, "two edited declarations share a line; edit them in separate batches", List.of(), Optional.empty());
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

   private static Refused refuse(Reason reason, String message) {
      return new Refused(reason, message, List.of(), Optional.empty());
   }
}

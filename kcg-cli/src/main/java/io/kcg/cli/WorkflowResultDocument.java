package io.kcg.cli;

import io.kcg.sir.application.api.*;
import io.kcg.sir.change.edit.CommandBatch;
import io.kcg.sir.semantic.schema.StructValue;
import io.kcg.sir.source.SourceSnapshot;
import java.util.Comparator;
import java.util.List;

/** Canonical new-workflow results; existing check/version documents retain their original protocols. */
final class WorkflowResultDocument {
    static final String PROTOCOL = "KCG-CLI-WORKFLOW-V1";
    private WorkflowResultDocument() {}

    static String render(ToolchainResult result) {
        var out = start("generate", result instanceof ToolchainResult.Success ? "GENERATED" : "FAILURE");
        if (result instanceof ToolchainResult.Success success) {
            field(out, "outputRoot", success.manifest().outputRoot().toString());
            sources(out, success.sources());
            out.append(",\"files\":[");
            boolean first = true;
            for (var file : success.manifest().files()) {
                if (!first) out.append(','); first = false;
                out.append('{'); JsonFields.field(out, "path", file.relativePath());
                field(out, "sha256", file.sha256Hex()); out.append(','); JsonFields.field(out, "byteCount", file.byteCount());
                out.append('}');
            }
            out.append(']');
        }
        return finish(out, result.diagnostics());
    }

    static String render(DeploymentPackageResult result) {
        var out = start("package", result instanceof DeploymentPackageResult.Exported ? "PACKAGE_EXPORTED" : "FAILURE");
        if (result instanceof DeploymentPackageResult.Exported exported) {
            field(out, "packageId", exported.packageId());
            field(out, "outputRoot", exported.manifest().outputRoot().toString());
            sources(out, exported.sources());
            field(out, "targetPlatform", "linux/amd64");
            exported.continuation().ifPresent(c -> {
                field(out, "basePackageId", c.basePackageId());
                out.append(','); JsonFields.field(out, "schemaVersion", c.schemaVersion());
                out.append(",\"changes\":[");
                for (int i = 0; i < c.changes().size(); i++) { if (i > 0) out.append(','); out.append('{'); JsonFields.field(out, "change", c.changes().get(i)); out.append('}'); }
                out.append("],\"steps\":[");
                for (int i = 0; i < c.steps().size(); i++) { if (i > 0) out.append(','); out.append('{'); JsonFields.field(out, "step", c.steps().get(i)); out.append('}'); }
                out.append(']');
            });
        }
        return finish(out, result.diagnostics());
    }

    static String render(SourceInspectionResult result) {
        var out = start("inspect", result instanceof SourceInspectionResult.Success ? "INSPECTED" : "FAILURE");
        if (result instanceof SourceInspectionResult.Success success) {
            field(out, "commandProtocolVersion", CommandJson.PROTOCOL);
            field(out, "protocol", CommandBatch.PROTOCOL);
            field(out, "softwareName", ((StructValue.Text)success.draft().root().slot("softwareName").value()).value());
            field(out, "baselineSha256", success.sources().sha256Hex());
            field(out, "editingScope", "DECLARATIONS_ONLY");
            sources(out, success.sources());
            out.append(",\"schema\":{\"rootKind\":").append(JsonStringEncoder.encode(success.schema().root().kind())).append(",\"nodes\":[");
            boolean first = true;
            for (var node : success.schema().nodes().values().stream().sorted(Comparator.comparing(n -> n.kind())).toList()) {
                if (!first) out.append(','); first = false;
                out.append('{'); JsonFields.field(out, "kind", node.kind());
                if (node.type().isEnum()) enumValues(out, node.type());
                out.append(",\"slots\":[");
                for (int i = 0; i < node.slots().size(); i++) {
                    if (i != 0) out.append(',');
                    var slot = node.slots().get(i);
                    out.append('{'); JsonFields.field(out, "key", slot.key());
                    field(out, "role", slot.kind().name()); field(out, "cardinality", slot.cardinality().name());
                    field(out, "valueType", slot.valueType().getSimpleName());
                    out.append(",\"keyed\":").append(slot.keyed()).append(",\"editable\":").append(CommandJson.editable(slot.kind()));
                    if (slot.valueType().isEnum()) {
                        out.append(",\"enumValues\":[");
                        Object[] constants = slot.valueType().getEnumConstants();
                        for (int j = 0; j < constants.length; j++) { if (j != 0) out.append(','); out.append(JsonStringEncoder.encode(((Enum<?>)constants[j]).name())); }
                        out.append(']');
                    }
                    out.append('}');
                }
                out.append("]}");
            }
            out.append("],\"unions\":{"); first = true;
            for (var union : success.schema().unions().entrySet().stream().sorted(Comparator.comparing(e -> e.getKey().getSimpleName())).toList()) {
                if (!first) out.append(','); first = false;
                out.append(JsonStringEncoder.encode(union.getKey().getSimpleName())).append(":[");
                for (int i = 0; i < union.getValue().size(); i++) { if (i != 0) out.append(','); out.append(JsonStringEncoder.encode(union.getValue().get(i).getSimpleName())); }
                out.append(']');
            }
            out.append("}},\"draft\":"); CommandJson.writeValue(out, new StructValue.Child(success.draft().root()));
            // Declarations of the draft that are not in the source: a use expanded into them, and an edit changes the use.
            out.append(",\"expandedDeclarations\":[");
            for (int i = 0; i < success.expanded().size(); i++) {
                if (i != 0) out.append(',');
                var expanded = success.expanded().get(i);
                out.append('{'); JsonFields.field(out, "kind", expanded.kind());
                field(out, "name", expanded.name()); field(out, "feature", expanded.feature());
                out.append(",\"arguments\":{");
                boolean firstArgument = true;
                for (var argument : new java.util.TreeMap<>(expanded.arguments()).entrySet()) {
                    if (!firstArgument) out.append(','); firstArgument = false;
                    out.append(JsonStringEncoder.encode(argument.getKey())).append(':').append(JsonStringEncoder.encode(argument.getValue()));
                }
                out.append('}'); field(out, "role", expanded.role()); out.append('}');
            }
            out.append(']');
        }
        return finish(out, result.diagnostics());
    }

    static String render(SourceEditResult result) {
        var out = start("edit", result instanceof SourceEditResult.Saved ? "CANDIDATE_SAVED" : result instanceof SourceEditResult.Refused ? "REFUSED" : "FAILURE");
        if (result instanceof SourceEditResult.Saved saved) {
            field(out, "candidateRoot", saved.candidateRoot().toString()); sources(out, saved.sources());
            out.append(",\"semanticChanges\":[");
            for (int i = 0; i < saved.delta().changes().size(); i++) {
                if (i != 0) out.append(','); var change = saved.delta().changes().get(i);
                out.append('{'); JsonFields.field(out, "action", change.kind().name());
                field(out, "path", change.path()); field(out, "before", change.before()); field(out, "after", change.after()); out.append('}');
            }
            out.append("],\"sourceChanges\":[");
            for (int i = 0; i < saved.changes().size(); i++) {
                if (i != 0) out.append(','); var located = saved.changes().get(i); var change = located.change();
                out.append('{'); JsonFields.field(out, "source", located.source().value());
                field(out, "action", change.type().name()); field(out, "declaration", change.declaration());
                field(out, "before", change.before()); field(out, "after", change.after()); out.append('}');
            }
            out.append(']');
        } else if (result instanceof SourceEditResult.Refused refused) {
            field(out, "reason", refused.refusal().reason().name()); field(out, "message", refused.refusal().message());
            out.append(",\"details\":[");
            for (int i = 0; i < refused.refusal().details().size(); i++) { if (i != 0) out.append(','); out.append(JsonStringEncoder.encode(refused.refusal().details().get(i))); }
            out.append(']');
        }
        return finish(out, result.diagnostics());
    }

    static String inputFailure(String outcome, String code, String message) {
        var out = start("edit", outcome); field(out, "code", code); field(out, "message", message);
        return finish(out, List.of());
    }
    private static void enumValues(StringBuilder out, Class<?> type) {
        out.append(",\"enumValues\":[");
        Object[] constants = type.getEnumConstants();
        for (int i = 0; i < constants.length; i++) { if (i != 0) out.append(','); out.append(JsonStringEncoder.encode(((Enum<?>)constants[i]).name())); }
        out.append(']');
    }
    private static StringBuilder start(String command, String outcome) {
        var out = new StringBuilder("{"); JsonFields.field(out, "protocolVersion", PROTOCOL);
        field(out, "command", command); field(out, "outcome", outcome); return out;
    }
    private static void field(StringBuilder out, String key, String value) { out.append(','); JsonFields.field(out, key, value); }
    private static void sources(StringBuilder out, SourceSnapshot sources) { sources(out, sources.manifest()); }
    private static void sources(StringBuilder out, io.kcg.sir.source.SourceSetManifest sources) {
        field(out, "entry", sources.entry().value()); field(out, "sourceSetSha256", sources.sha256Hex());
        out.append(",\"sources\":["); boolean first = true;
        for (var file : sources.files()) {
            if (!first) out.append(','); first = false;
            out.append('{'); JsonFields.field(out, "path", file.sourceId().value());
            field(out, "sha256", file.sha256Hex()); out.append(','); JsonFields.field(out, "byteCount", file.byteCount()); out.append('}');
        }
        out.append(']');
    }
    private static String finish(StringBuilder out, List<ExecutionDiagnostic> diagnostics) {
        out.append(",\"diagnostics\":[");
        for (int i = 0; i < diagnostics.size(); i++) {
            if (i != 0) out.append(','); var d = diagnostics.get(i);
            out.append('{'); JsonFields.field(out, "code", d.code()); field(out, "stage", d.stage().name());
            field(out, "severity", d.severity().name()); field(out, "message", d.message());
            if (d.sourceSpan().isPresent()) {
                var span = d.sourceSpan().get(); field(out, "source", span.source().value());
                out.append(','); JsonFields.field(out, "line", span.start().line());
                out.append(','); JsonFields.field(out, "column", span.start().column());
            }
            if (d.relativePath().isPresent()) field(out, "relativePath", d.relativePath().get());
            out.append('}');
        }
        return out.append("]}").toString();
    }
}

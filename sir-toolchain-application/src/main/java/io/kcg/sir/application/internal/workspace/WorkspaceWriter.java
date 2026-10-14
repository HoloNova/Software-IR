package io.kcg.sir.application.internal.workspace;

import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.application.api.FileDelta;
import io.kcg.sir.application.api.ProjectToolchainRequest;
import io.kcg.sir.application.api.SourceDelta;
import io.kcg.sir.application.api.WorkspacePlan;
import io.kcg.sir.application.internal.ProjectSourceReader;
import io.kcg.sir.application.internal.projectbaseline.ProjectBaselineVerification;
import io.kcg.sir.application.internal.projectbaseline.SecureFileAccess;
import io.kcg.sir.source.SourceSnapshot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Carries out a {@link WorkspacePlanning.Ready} plan on disk: the generated files first, then the SIR
 * sources, so that the sources, which define "the current version", change last.
 *
 * <p>There is no journal, no backup and no recovery here, on purpose (ADR-027). History is in Git, and the
 * caller saved the previous version there before calling. What this class guarantees instead is that it can
 * be run again. Every step first looks at what is on disk: a file that already holds the new bytes is left
 * alone, a file that is already gone stays gone, and a file that is neither the old nor the new version
 * stops the run. So an interrupted run is finished by running it again, and anything unexpected is reported
 * rather than overwritten.
 *
 * <p>Each file is written next to the output root first and then moved into place with one atomic move
 * whose target is bound to the identity it had when it was checked, so a reader never sees half a file and a
 * file someone replaced in between is not overwritten.
 */
public final class WorkspaceWriter {
    /** Where new bytes wait before they are moved into place; removed when the run ends. */
    public static final String STAGING = ".kcg-staging";
    private static final int MAX_DIRECTORY_ENTRIES = 4096;

    private WorkspaceWriter() {
    }

    public static void apply(WorkspacePlanning.Ready ready, Path sourceRoot, Path outputRoot, SecureFileAccess output, SecureFileAccess sources, Consumer<String> checkpoint)
            throws IOException {
        Map<String, byte[]> generated = new HashMap<>();
        ready.candidate().compilation().generatedFiles().forEach(f -> generated.put(f.relativePath(), f.content().getBytes(StandardCharsets.UTF_8)));
        SourceSnapshot candidateSources = ready.candidate().bundle().sources();

        clearStaleStaging(output);
        output.createDirectory(STAGING);
        checkpoint.accept("staging-created");
        int[] staged = {0};

        for (WorkspacePlan.FileOp op : ready.plan().files()) {
            if (op.action() == WorkspacePlan.Action.DELETE) {
                continue;
            }

            FileDelta.Observation now = WorkspacePlanning.observe(output, op.path());
            if (now instanceof FileDelta.Observation.Present present && present.sha256Hex().equals(op.afterSha256())) {
                checkpoint.accept("already:" + op.path());
                continue;
            }

            Object targetKey = null;
            if (op.action() == WorkspacePlan.Action.CREATE) {
                expect(now instanceof FileDelta.Observation.Absent, "a file the new version creates now exists: " + op.path());
                ensureDirectories(output, parent(op.path()));
            } else {
                expect(now instanceof FileDelta.Observation.Present present && present.sha256Hex().equals(op.beforeSha256()), "a generated file changed while applying: " + op.path());
                targetKey = output.identity(op.path());
            }

            place(output, output, STAGING + "/" + staged[0]++, op.path(), generated.get(op.path()), targetKey, checkpoint);
            checkpoint.accept("applied:" + op.path());
        }

        for (WorkspacePlan.FileOp op : ready.plan().files()) {
            if (op.action() != WorkspacePlan.Action.DELETE) {
                continue;
            }

            FileDelta.Observation now = WorkspacePlanning.observe(output, op.path());
            if (now instanceof FileDelta.Observation.Absent) {
                checkpoint.accept("already:" + op.path());
                continue;
            }

            expect(now instanceof FileDelta.Observation.Present present && present.sha256Hex().equals(op.beforeSha256()), "a generated file changed while applying: " + op.path());
            output.deleteOwned(op.path(), output.identity(op.path()));
            checkpoint.accept("deleted:" + op.path());
        }

        removeEmptyDirectories(ready.plan().files(), generated.keySet(), output, checkpoint);
        ProjectBaselineVerification.verifyOutput(ready.candidate().bundle());
        checkpoint.accept("output-verified");

        applySources(ready.plan().sources(), candidateSources, output, sources, staged, checkpoint);
        removeEmptySourceDirectories(ready.plan().sources(), sources, checkpoint);
        verifySources(sourceRoot, outputRoot, candidateSources);
        checkpoint.accept("sources-verified");

        expect(output.list(STAGING, MAX_DIRECTORY_ENTRIES).isEmpty(), "the staging directory still holds files");
        output.deleteOwnedDirectory(STAGING, output.directoryIdentity(STAGING));
        checkpoint.accept("staging-removed");
    }

    /** Writes the bytes into staging, then moves them to the target with one atomic move. */
    private static void place(SecureFileAccess staging, SecureFileAccess target, String stagedPath, String targetPath, byte[] bytes, Object targetKey, Consumer<String> checkpoint)
            throws IOException {
        staging.writeNew(stagedPath, bytes, checkpoint);
        Object key = staging.identity(stagedPath);
        staging.moveOwned(stagedPath, key, target, targetPath, targetKey, checkpoint);
    }

    /**
     * What an interrupted run leaves behind is files named by number; anything else in there is not ours and
     * stops the run. Public because an apply that finds nothing left to do still clears what the stopped run left.
     */
    public static void clearStaleStaging(SecureFileAccess output) throws IOException {
        if (!output.exists(STAGING)) {
            return;
        }

        Object directoryKey = output.directoryIdentity(STAGING);
        Set<String> names = output.list(STAGING, MAX_DIRECTORY_ENTRIES);
        for (String name : names) {
            expect(name.matches("[0-9]{1,6}"), "the staging directory holds something this tool did not write: " + STAGING + "/" + name);
        }

        for (String name : names) {
            String path = STAGING + "/" + name;
            output.deleteOwned(path, output.identity(path));
        }

        output.deleteOwnedDirectory(STAGING, directoryKey);
    }

    /**
     * The sources are the one place where a stop in the middle can leave files from two versions side by side,
     * so everything is written into staging first and the moves that make it visible follow back to back:
     * new files, then changed files, then removals. A source file nothing refers to yet is harmless, and a
     * removed file is only removed after the file that listed it has been replaced.
     */
    private static void applySources(SourceDelta delta, SourceSnapshot candidate, SecureFileAccess output, SecureFileAccess sources, int[] staged, Consumer<String> checkpoint)
            throws IOException {
        record Pending(SourceDelta.Entry entry, String stagedPath, Object targetKey) {
        }

        List<Pending> moves = new ArrayList<>();
        for (SourceDelta.Action action : List.of(SourceDelta.Action.ADDED, SourceDelta.Action.CHANGED)) {
            for (SourceDelta.Entry entry : delta.entries()) {
                if (entry.action() != action) {
                    continue;
                }

                String path = entry.source().value();
                FileDelta.Observation now = WorkspacePlanning.observe(sources, path);
                if (now instanceof FileDelta.Observation.Present present && present.sha256Hex().equals(entry.afterSha256())) {
                    checkpoint.accept("already:" + path);
                    continue;
                }

                Object targetKey = null;
                if (action == SourceDelta.Action.ADDED) {
                    expect(now instanceof FileDelta.Observation.Absent, "a source the new version adds now exists: " + path);
                    ensureDirectories(sources, parent(path));
                } else {
                    expect(now instanceof FileDelta.Observation.Present present && present.sha256Hex().equals(entry.beforeSha256()), "a source changed while applying: " + path);
                    targetKey = sources.identity(path);
                }

                String stagedPath = STAGING + "/" + staged[0]++;
                output.writeNew(stagedPath, candidate.bytes(entry.source()), checkpoint);
                moves.add(new Pending(entry, stagedPath, targetKey));
            }
        }

        checkpoint.accept("sources-staged");
        for (Pending move : moves) {
            output.moveOwned(move.stagedPath(), output.identity(move.stagedPath()), sources, move.entry().source().value(), move.targetKey(), checkpoint);
            checkpoint.accept("source-applied:" + move.entry().source().value());
        }

        for (SourceDelta.Entry entry : delta.entries()) {
            if (entry.action() != SourceDelta.Action.REMOVED) {
                continue;
            }

            String path = entry.source().value();
            FileDelta.Observation now = WorkspacePlanning.observe(sources, path);
            if (now instanceof FileDelta.Observation.Absent) {
                checkpoint.accept("already:" + path);
                continue;
            }

            expect(now instanceof FileDelta.Observation.Present present && present.sha256Hex().equals(entry.beforeSha256()), "a source changed while applying: " + path);
            sources.deleteOwned(path, sources.identity(path));
            checkpoint.accept("source-deleted:" + path);
        }
    }

    /** A directory goes away only when the new version has nothing under it and nothing else is left in it. */
    private static void removeEmptyDirectories(List<WorkspacePlan.FileOp> files, Set<String> wantedFiles, SecureFileAccess output, Consumer<String> checkpoint) throws IOException {
        Set<String> wanted = new HashSet<>();
        wantedFiles.forEach(path -> wanted.addAll(ancestors(path)));
        Set<String> consider = new TreeSet<>();
        for (WorkspacePlan.FileOp op : files) {
            if (op.action() == WorkspacePlan.Action.DELETE) {
                for (String directory : ancestors(op.path())) {
                    if (!wanted.contains(directory)) {
                        consider.add(directory);
                    }
                }
            }
        }

        removeIfEmpty(consider, output, checkpoint);
    }

    private static void removeEmptySourceDirectories(SourceDelta delta, SecureFileAccess sources, Consumer<String> checkpoint) throws IOException {
        Set<String> remaining = new HashSet<>();
        for (SourceDelta.Entry entry : delta.entries()) {
            if (entry.action() != SourceDelta.Action.REMOVED) {
                remaining.addAll(ancestors(entry.source().value()));
            }
        }

        Set<String> consider = new TreeSet<>();
        for (SourceDelta.Entry entry : delta.entries()) {
            if (entry.action() == SourceDelta.Action.REMOVED) {
                for (String directory : ancestors(entry.source().value())) {
                    if (!remaining.contains(directory)) {
                        consider.add(directory);
                    }
                }
            }
        }

        removeIfEmpty(consider, sources, checkpoint);
    }

    private static void removeIfEmpty(Set<String> directories, SecureFileAccess access, Consumer<String> checkpoint) throws IOException {
        List<String> deepestFirst = new ArrayList<>(directories);
        deepestFirst.sort(Comparator.comparingInt((String d) -> d.split("/").length).reversed().thenComparing(Comparator.<String>reverseOrder()));
        for (String directory : deepestFirst) {
            if (!access.exists(directory) || !access.list(directory, MAX_DIRECTORY_ENTRIES).isEmpty()) {
                continue;
            }

            access.deleteOwnedDirectory(directory, access.directoryIdentity(directory));
            checkpoint.accept("directory-removed:" + directory);
        }
    }

    /** After the move, the sources on disk must read back as exactly the candidate. */
    private static void verifySources(Path sourceRoot, Path outputRoot, SourceSnapshot candidate) throws IOException {
        List<ExecutionDiagnostic> diagnostics = new ArrayList<>();
        var read = ProjectSourceReader.read(new ProjectToolchainRequest(sourceRoot, candidate.entry(), outputRoot), diagnostics);
        expect(read.isPresent() && read.get().snapshot().sha256Hex().equals(candidate.sha256Hex()), "the sources on disk are not the candidate after writing them: " + diagnostics);
    }

    private static void ensureDirectories(SecureFileAccess access, String directory) throws IOException {
        if (directory.isEmpty()) {
            return;
        }

        StringBuilder path = new StringBuilder();
        for (String part : directory.split("/")) {
            path.append(path.length() == 0 ? "" : "/").append(part);
            if (access.exists(path.toString())) {
                access.directoryIdentity(path.toString());
            } else {
                access.createDirectory(path.toString());
            }
        }
    }

    /** Every directory above a file, from the top down: {@code a/b/c.txt} gives {@code a} and {@code a/b}. */
    private static List<String> ancestors(String path) {
        List<String> directories = new ArrayList<>();
        int slash = path.indexOf('/');
        while (slash >= 0) {
            directories.add(path.substring(0, slash));
            slash = path.indexOf('/', slash + 1);
        }

        return directories;
    }

    private static String parent(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private static void expect(boolean condition, String message) throws IOException {
        if (!condition) {
            throw new IOException(message);
        }
    }
}

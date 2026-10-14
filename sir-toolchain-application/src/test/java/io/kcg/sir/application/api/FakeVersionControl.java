package io.kcg.sir.application.api;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** A Git that records what it is asked and answers as told, so a test can refuse or fail any step. */
final class FakeVersionControl implements VersionControl {
    State state = State.REPOSITORY;
    String detail = "fake";
    Optional<String> initializeFailure = Optional.empty();
    /** The 1-based snapshot that fails, or 0 for none. */
    int failingSnapshot;
    final List<String> calls = new ArrayList<>();
    final List<List<String>> snapshots = new ArrayList<>();
    private int count;

    @Override
    public Status inspect(Path root) {
        this.calls.add("inspect");
        return new Status(this.state, this.detail);
    }

    @Override
    public Optional<String> initialize(Path root) {
        this.calls.add("initialize");
        if (this.initializeFailure.isEmpty()) {
            this.state = State.REPOSITORY;
        }

        return this.initializeFailure;
    }

    @Override
    public SnapshotOutcome snapshot(Path root, List<String> paths, String message) {
        this.calls.add("snapshot");
        this.snapshots.add(List.copyOf(paths));
        this.count++;
        if (this.count == this.failingSnapshot) {
            return new Failed("fake snapshot " + this.count + " failed");
        }

        return new Taken(Optional.of(String.format("%040x", this.count)), true);
    }

    long snapshotCalls() {
        return this.calls.stream().filter("snapshot"::equals).count();
    }
}

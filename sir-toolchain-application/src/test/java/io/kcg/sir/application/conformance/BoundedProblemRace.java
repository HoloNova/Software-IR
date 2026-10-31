package io.kcg.sir.application.conformance;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Variable-sized real HTTP competitions: all N connections/transactions must wait before release. */
final class BoundedProblemRace {
    private static final int READY_SECONDS = 5;
    private static final int OBSERVE_SECONDS = 5;
    private static final int REQUEST_SECONDS = 30;
    private static final int ROUND_SECONDS = 35;
    private static final int STOP_SECONDS = 5;
    private static final long OBSERVE_INTERVAL_MILLIS = 50;
    record Call(String method, int actor, String path, String body) { }
    record Outcome(Call call, HttpAssertionClient.Response response, String error) { }
    private record Event(int client, String phase, Instant at, String detail) { }
    private BoundedProblemRace() { }

    static List<Outcome> run(BoundedProblemHttp a, String label, String table, long id, List<Call> calls) throws Exception {
        if (calls.size() < 2 || calls.size() > 6) throw new IllegalArgumentException("expected 2..6 independent clients");
        try (Connection blocker = a.fixture.lock(table, id)) {
            var pool = Executors.newFixedThreadPool(calls.size());
            var ready = new CountDownLatch(calls.size());
            var start = new CountDownLatch(1);
            var events = new ConcurrentLinkedQueue<Event>();
            var futures = new ArrayList<Future<Outcome>>();
            try {
                for (int index = 0; index < calls.size(); index++) {
                    int client = index;
                    futures.add(pool.submit(() -> request(a, calls.get(client), client, ready, start, events)));
                }
                boolean prepared = ready.await(READY_SECONDS, TimeUnit.SECONDS);
                a.slice.check(label + " all clients ready", prepared, "remaining=" + ready.getCount());
                if (!prepared) throw new IOException("HTTP clients did not all become ready");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(ROUND_SECONDS);
                long root = BoundedProblemFixture.connectionId(blocker);
                start.countDown();
                try {
                    boolean proof = awaitContention(a, label, root, futures);
                    a.slice.check(label + " all " + calls.size() + " distinct runtime connections/transactions wait on " + table + "/" + id,
                            proof, "blockerConnection=" + root);
                } finally { blocker.rollback(); }
                var results = collect(a.slice, label, futures, calls, deadline);
                if (results.stream().anyMatch(r -> r.error() != null)) {
                    throw new IOException("HTTP round incomplete; stop application before any fixture reset");
                }
                return results;
            } finally {
                // Release the database barrier before cancelling workers, including an early-ready failure.
                try { blocker.rollback(); }
                finally {
                    start.countDown();
                    try { stop(pool, futures); }
                    finally { timeline(a, label, events, calls.size()); }
                }
            }
        }
    }

    private static Outcome request(BoundedProblemHttp a, Call call, int index, CountDownLatch ready,
                                   CountDownLatch start, ConcurrentLinkedQueue<Event> events) {
        HttpClient client = null;
        try {
            client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(READY_SECONDS)).build();
            events.add(new Event(index, "ready", Instant.now(), "actor=" + call.actor()));
            ready.countDown();
            if (!start.await(READY_SECONDS * 2L, TimeUnit.SECONDS)) throw new TimeoutException("start not released");
            events.add(new Event(index, "start", Instant.now(), call.method() + " " + call.path()));
            var request = HttpRequest.newBuilder(URI.create(a.slice.baseUrl() + call.path()))
                    .timeout(Duration.ofSeconds(REQUEST_SECONDS)).header("Content-Type", "application/json")
                    .method(call.method(), HttpRequest.BodyPublishers.ofString(call.body()));
            a.actor(call.actor()).forEach(request::header);
            var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            events.add(new Event(index, "end", Instant.now(), "status=" + response.statusCode()));
            return new Outcome(call, new HttpAssertionClient.Response(response.statusCode(), response.body()), null);
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            String detail = BusinessSliceHarness.describe(error);
            events.add(new Event(index, "end", Instant.now(), "error=" + detail));
            return new Outcome(call, null, detail);
        } finally { if (client != null) client.shutdownNow(); }
    }

    private static boolean awaitContention(BoundedProblemHttp a, String label, long blocker, List<Future<Outcome>> futures) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(OBSERVE_SECONDS);
        List<BlogLockWaits.Edge> previous = List.of();
        while (System.nanoTime() < deadline) {
            var edges = a.fixture.waits();
            if (!edges.equals(previous)) a.slice.reportLine(label + " lockWaits=" + edges);
            previous = edges;
            if (futures.stream().anyMatch(Future::isDone)) return false;
            var proof = BlogLockWaits.observe(edges, blocker, a.fixture.runtimeUsername());
            if (proof.ready(futures.size()) && System.nanoTime() <= deadline) {
                a.slice.reportLine(label + " contentionProof=" + proof);
                return true;
            }
            Thread.sleep(OBSERVE_INTERVAL_MILLIS);
        }
        return false;
    }

    static List<Outcome> collect(BusinessSliceHarness slice, String label, List<? extends Future<Outcome>> futures,
                                 List<Call> calls, long deadline) throws InterruptedException {
        if (calls.size() != futures.size()) throw new IllegalArgumentException("missing request future");
        var outcomes = new ArrayList<Outcome>();
        for (int index = 0; index < futures.size(); index++) {
            Outcome outcome;
            try { outcome = futures.get(index).get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
            catch (TimeoutException error) {
                futures.get(index).cancel(true);
                outcome = new Outcome(calls.get(index), null, "35-second round deadline elapsed");
            } catch (ExecutionException error) {
                outcome = new Outcome(calls.get(index), null, BusinessSliceHarness.describe(error.getCause()));
            }
            if (outcome.error() == null && System.nanoTime() > deadline) outcome = new Outcome(outcome.call(), outcome.response(), "35-second round deadline elapsed");
            slice.check(label + " client " + index + " completed", outcome.error() == null,
                    outcome.error() == null ? "HTTP " + outcome.response().statusCode() : outcome.error());
            if (outcome.response() != null) slice.record(label + " client " + index, outcome.response());
            outcomes.add(outcome);
        }
        return List.copyOf(outcomes);
    }

    private static void stop(ExecutorService pool, List<Future<Outcome>> futures) throws IOException, InterruptedException {
        futures.stream().filter(f -> !f.isDone()).forEach(f -> f.cancel(true));
        pool.shutdownNow();
        if (!pool.awaitTermination(STOP_SECONDS, TimeUnit.SECONDS)) throw new IOException("oracle HTTP workers did not terminate");
    }
    private static void timeline(BoundedProblemHttp a, String label, ConcurrentLinkedQueue<Event> events, int clients) {
        var ordered = events.stream().sorted(Comparator.comparing(Event::at).thenComparingInt(Event::client)).toList();
        ordered.forEach(e -> a.slice.reportLine(label + " timeline=" + e));
        for (String phase : List.of("ready", "start", "end")) {
            long count = ordered.stream().filter(e -> e.phase().equals(phase)).map(Event::client).distinct().count();
            a.slice.check(label + " records all " + phase + " events", count == clients, "events=" + count);
        }
    }
}

package io.kcg.sir.application.conformance;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLException;
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

/** Six independent HTTP clients; the server's wait graph, not a sleep, proves actual contention. */
final class BlogConcurrentRequests {
    static final int CLIENTS = 6;
    private static final long READY_SECONDS = 5;
    private static final long LOCK_WAIT_SECONDS = 5;
    private static final long REQUEST_SECONDS = 30;
    private static final long ROUND_SECONDS = 35;
    private static final long STOP_SECONDS = 5;
    private static final long OBSERVE_INTERVAL_MS = 50;
    record Call(int actor, String path, String body) { }
    record Outcome(Call call, HttpAssertionClient.Response response, String error) { }
    private record Event(int client, String phase, Instant at, String detail) { }
    private BlogConcurrentRequests() { }

    static List<Outcome> run(BlogAssertions a, String label, long post, List<Call> calls) throws Exception {
        if (calls.size() != CLIENTS) throw new IllegalArgumentException("a blog competition has exactly six clients");
        try (Connection blocker = a.fixture.lockPost(post)) {
            var pool = Executors.newFixedThreadPool(CLIENTS);
            var ready = new CountDownLatch(CLIENTS);
            var start = new CountDownLatch(1);
            var events = new ConcurrentLinkedQueue<Event>();
            List<Future<Outcome>> futures = List.of();
            try {
                a.slice.reportLine("blogHeldPost[" + label + "] post=" + post + " blockerConnection=" + BlogFixture.connectionId(blocker));
                futures = launch(pool, a.slice.baseUrl(), calls, ready, start, events);
                boolean prepared = ready.await(READY_SECONDS, TimeUnit.SECONDS);
                a.slice.check(label + " six independent clients reach the ready latch", prepared, "remaining=" + ready.getCount());
                if (!prepared) throw new IOException("not all six blog clients became ready");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(ROUND_SECONDS);
                start.countDown();
                return observeReleaseAndCollect(a, label, blocker, futures, calls, deadline);
            } finally {
                start.countDown();
                try { stop(pool, futures); } finally { recordEvents(a, label, events); }
            }
        }
    }

    private static List<Future<Outcome>> launch(ExecutorService pool, String baseUrl, List<Call> calls,
                                                CountDownLatch ready, CountDownLatch start, ConcurrentLinkedQueue<Event> events) {
        var futures = new ArrayList<Future<Outcome>>();
        for (int index = 0; index < calls.size(); index++) {
            int client = index;
            futures.add(pool.submit(() -> request(baseUrl, calls.get(client), client, ready, start, events)));
        }
        return List.copyOf(futures);
    }

    private static Outcome request(String baseUrl, Call call, int index, CountDownLatch ready,
                                   CountDownLatch start, ConcurrentLinkedQueue<Event> events) {
        HttpClient client = null;
        try {
            client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(READY_SECONDS)).build();
            events.add(new Event(index, "ready", Instant.now(), "actor=" + call.actor()));
            ready.countDown();
            if (!start.await(READY_SECONDS * 2, TimeUnit.SECONDS)) throw new TimeoutException("start latch not released");
            events.add(new Event(index, "start", Instant.now(), call.path()));
            var request = HttpRequest.newBuilder(URI.create(baseUrl + call.path())).timeout(Duration.ofSeconds(REQUEST_SECONDS))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(call.body()));
            BlogAssertions.actor(call.actor()).forEach(request::header);
            var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            events.add(new Event(index, "end", Instant.now(), "status=" + response.statusCode()));
            return new Outcome(call, new HttpAssertionClient.Response(response.statusCode(), response.body()), null);
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            String detail = BusinessSliceHarness.describe(error);
            events.add(new Event(index, "end", Instant.now(), "error=" + detail));
            return new Outcome(call, null, detail);
        } finally {
            if (client != null) client.shutdownNow();
        }
    }

    private static List<Outcome> observeReleaseAndCollect(BlogAssertions a, String label, Connection blocker,
            List<Future<Outcome>> futures, List<Call> calls, long deadline) throws Exception {
        SQLException failure = null;
        try {
            long root = BlogFixture.connectionId(blocker);
            boolean proof = awaitContention(a, label, root, futures);
            a.slice.check(label + " six distinct runtime connections and transactions demonstrably wait on the held post",
                    proof, "blockerConnection=" + root + "; target transactions=" + CLIENTS);
        } catch (SQLException error) {
            failure = error;
            a.slice.check(label + " lock observation completed", false, BusinessSliceHarness.describe(error));
        } finally {
            try { blocker.rollback(); }
            catch (SQLException release) {
                if (failure == null) failure = release; else failure.addSuppressed(release);
            }
        }
        List<Outcome> results = collect(a.slice, label, futures, calls, deadline);
        if (failure != null) throw failure;
        if (results.stream().anyMatch(r -> r.error() != null)) {
            // A client timeout cannot prove that the server finished: abort before any fixture reset.
            throw new IOException("blog HTTP competition did not complete cleanly; stop the application before resetting data");
        }
        return results;
    }

    private static boolean awaitContention(BlogAssertions a, String label, long blocker, List<Future<Outcome>> futures) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LOCK_WAIT_SECONDS);
        List<BlogLockWaits.Edge> previous = List.of();
        while (System.nanoTime() < deadline) {
            var edges = a.fixture.waits();
            if (!edges.equals(previous)) a.slice.reportLine("blogLockWaits[" + label + "] blocker=" + blocker + " edges=" + edges);
            previous = edges;
            var observed = BlogLockWaits.observe(edges, blocker, a.fixture.runtimeUsername());
            if (futures.stream().anyMatch(Future::isDone)) {
                a.slice.reportLine("blogLockWaits[" + label + "] a request completed before full contention");
                return false;
            }
            if (observed.ready(CLIENTS) && System.nanoTime() <= deadline) {
                a.slice.reportLine("blogContentionProof[" + label + "] connections=" + observed.connections()
                        + " transactions=" + observed.transactions());
                return true;
            }
            Thread.sleep(OBSERVE_INTERVAL_MS);
        }
        a.slice.reportLine("blogLockWaits[" + label + "] five-second observation deadline elapsed");
        return false;
    }

    static List<Outcome> collect(BusinessSliceHarness slice, String label, List<Future<Outcome>> futures,
                                        List<Call> calls, long deadline) throws InterruptedException {
        var outcomes = new ArrayList<Outcome>();
        for (int index = 0; index < futures.size(); index++) {
            Outcome outcome;
            try {
                outcome = futures.get(index).get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (TimeoutException error) {
                futures.get(index).cancel(true);
                outcome = new Outcome(calls.get(index), null, "35-second round collection deadline elapsed");
            } catch (ExecutionException error) {
                outcome = new Outcome(calls.get(index), null, BusinessSliceHarness.describe(error.getCause()));
            }
            if (outcome.error() == null && System.nanoTime() > deadline) {
                outcome = new Outcome(outcome.call(), outcome.response(), "35-second round collection deadline elapsed");
            }
            slice.check(label + " client " + index + " completed its real HTTP request", outcome.error() == null,
                    outcome.error() == null ? "HTTP " + outcome.response().statusCode() : outcome.error());
            if (outcome.response() != null) slice.record(label + " client " + index, outcome.response());
            outcomes.add(outcome);
        }
        return List.copyOf(outcomes);
    }

    private static void stop(ExecutorService pool, List<Future<Outcome>> futures) throws IOException, InterruptedException {
        futures.stream().filter(f -> !f.isDone()).forEach(f -> f.cancel(true));
        pool.shutdownNow();
        if (!pool.awaitTermination(STOP_SECONDS, TimeUnit.SECONDS)) throw new IOException("blog client workers did not terminate");
    }

    private static void recordEvents(BlogAssertions a, String label, ConcurrentLinkedQueue<Event> events) {
        var ordered = events.stream().sorted(Comparator.comparing(Event::at).thenComparingInt(Event::client)).toList();
        ordered.forEach(e -> a.slice.reportLine("blogHttpTimeline[" + label + "] client=" + e.client() + " " + e.phase()
                + " at=" + e.at() + " " + e.detail()));
        for (String phase : List.of("ready", "start", "end")) {
            long count = ordered.stream().filter(e -> phase.equals(e.phase())).map(Event::client).distinct().count();
            a.slice.check(label + " records all six actual " + phase + " events", count == CLIENTS, "events=" + count);
        }
    }
}

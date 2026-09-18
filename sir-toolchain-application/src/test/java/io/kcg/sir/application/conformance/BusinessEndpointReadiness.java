package io.kcg.sir.application.conformance;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

/**
 * Proves Spring Context readiness by retrying the scenario's known business
 * endpoint until the exact expected readiness status is received, the
 * process exits, or the timeout expires.
 *
 * <p>TCP connect success is only a hint; it never proves Context success.
 * Arbitrary 404, TCP connect, or log line does not advance Context state.
 */
public final class BusinessEndpointReadiness {

    private final HttpClient httpClient;
    private final int expectedReadinessStatus;

    public BusinessEndpointReadiness(int expectedReadinessStatus) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.expectedReadinessStatus = expectedReadinessStatus;
    }

    /**
     * Wait until the readiness endpoint returns the expected status, the
     * process exits, or the timeout expires.
     *
     * @param readinessUrl the business endpoint URL to probe
     * @param process      the Spring process (to detect exit)
     * @param timeoutMs    the total timeout in milliseconds
     * @param pollIntervalMs the poll interval
     * @return true if readiness was achieved
     */
    public boolean waitUntilReady(String readinessUrl, Process process,
                                  long timeoutMs, long pollIntervalMs) {
        Objects.requireNonNull(readinessUrl, "readinessUrl");
        return awaitStatus(() -> getRequest(readinessUrl), process, timeoutMs, pollIntervalMs);
    }

    /**
     * GET variant of {@link #waitUntilReady}. Semantically identical; it exists as a
     * named GET entry point so the orchestration reads symmetrically against
     * {@link #waitUntilReadyPost}.
     *
     * @param readinessUrl   the business endpoint URL to probe
     * @param process        the Spring process (to detect exit)
     * @param timeoutMs      the total timeout in milliseconds
     * @param pollIntervalMs the poll interval
     * @return true if readiness was achieved
     */
    public boolean waitUntilReadyGet(String readinessUrl, Process process,
                                     long timeoutMs, long pollIntervalMs) {
        return waitUntilReady(readinessUrl, process, timeoutMs, pollIntervalMs);
    }

    /**
     * Wait until a POST readiness endpoint returns the expected status, the process
     * exits, or the timeout expires.
     *
     * <p>Command capabilities are exposed as POST routes, so readiness for those
     * scenarios is proven by posting the scenario's readiness body and expecting the
     * status the scenario declares — for example 400 from a validation rejection,
     * which proves the context, the route, and the validation layer are all live.
     *
     * @param readinessUrl   the business endpoint URL to probe
     * @param jsonBody       the JSON request body
     * @param headers        extra headers (never credentials)
     * @param process        the Spring process (to detect exit)
     * @param timeoutMs      the total timeout in milliseconds
     * @param pollIntervalMs the poll interval
     * @return true if readiness was achieved
     */
    public boolean waitUntilReadyPost(String readinessUrl, String jsonBody,
                                      java.util.Map<String, String> headers,
                                      Process process, long timeoutMs, long pollIntervalMs) {
        Objects.requireNonNull(readinessUrl, "readinessUrl");
        return awaitStatus(() -> postRequest(readinessUrl, jsonBody, headers),
                process, timeoutMs, pollIntervalMs);
    }

    private boolean awaitStatus(java.util.function.Supplier<HttpRequest> requestFactory,
                                Process process, long timeoutMs, long pollIntervalMs) {
        Objects.requireNonNull(process, "process");
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                return false;
            }
            try {
                HttpResponse<String> response = httpClient.send(requestFactory.get(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == expectedReadinessStatus) {
                    return true;
                }
            } catch (IOException | InterruptedException e) {
                // Connection refused is expected until the context is up; keep retrying.
            }
            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static HttpRequest getRequest(String readinessUrl) {
        return HttpRequest.newBuilder()
                .uri(URI.create(readinessUrl))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();
    }

    private static HttpRequest postRequest(String readinessUrl, String jsonBody,
                                            java.util.Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(readinessUrl))
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "application/json");
        if (headers != null) {
            headers.forEach(builder::header);
        }
        return builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody == null ? "" : jsonBody))
                .build();
    }
}
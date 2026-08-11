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
        Objects.requireNonNull(process, "process");
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                return false;
            }
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(readinessUrl))
                        .timeout(Duration.ofSeconds(3))
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == expectedReadinessStatus) {
                    return true;
                }
            } catch (IOException | InterruptedException e) {
                // Connection refused 鈥?keep retrying.
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
}
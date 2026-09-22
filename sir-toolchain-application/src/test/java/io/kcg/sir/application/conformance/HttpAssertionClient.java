package io.kcg.sir.application.conformance;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * HTTP assertion client for sending fresh, independent requests after
 * Context readiness is proved. Supports GET and POST with optional headers
 * and body.
 */
public final class HttpAssertionClient {

    private final HttpClient httpClient;

    public HttpAssertionClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * Send a GET request.
     *
     * @param url     the URL
     * @param headers optional headers (may be empty)
     * @return the response (status + body)
     */
    public Response get(String url, Map<String, String> headers) throws IOException, InterruptedException {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(headers, "headers");
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET();
        headers.forEach(builder::header);
        HttpResponse<String> response = httpClient.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }

    /**
     * Send a POST request with a JSON body.
     */
    public Response postJson(String url, Map<String, String> headers, String jsonBody)
            throws IOException, InterruptedException {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(jsonBody, "jsonBody");
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        headers.forEach(builder::header);
        HttpResponse<String> response = httpClient.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }

    /**
     * Send a PATCH request with a JSON body.
     *
     * <p>A change set is delivered as PATCH, so the write slice's requests have to be sent the way a
     * client would send them rather than tunnelled through POST.
     */
    public Response patchJson(String url, Map<String, String> headers, String jsonBody)
            throws IOException, InterruptedException {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(jsonBody, "jsonBody");
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(jsonBody));
        headers.forEach(builder::header);
        HttpResponse<String> response = httpClient.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }

    /**
     * One HTTP response.
     *
     * @param statusCode the HTTP status code
     * @param body       the response body
     */
    public record Response(int statusCode, String body) {
        public Response {
            Objects.requireNonNull(body, "body");
        }
    }
}
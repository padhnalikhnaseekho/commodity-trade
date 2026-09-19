package io.commodity.seed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** A minimal JSON-over-HTTP client for the seeder. Any non-2xx answer is an error carrying the server's problem body, so a failed seed says exactly why. */
final class Http {

    /** A non-success answer from the application. */
    static final class ApiException extends RuntimeException {
        final int status;
        ApiException(int status, String method, String path, String body) {
            super(method + " " + path + " -> " + status + " " + body);
            this.status = status;
        }
    }

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final String base;

    Http(String base) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    JsonNode post(String path, Map<String, ?> body) {
        return send("POST", path, body);
    }

    JsonNode get(String path) {
        return send("GET", path, null);
    }

    private JsonNode send(String method, String path, Map<String, ?> body) {
        try {
            var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json");
            var request = (body == null ? builder.method(method, HttpRequest.BodyPublishers.noBody())
                    : builder.method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw new ApiException(response.statusCode(), method, path, response.body());
            return response.body() == null || response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("cannot reach " + base + path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}

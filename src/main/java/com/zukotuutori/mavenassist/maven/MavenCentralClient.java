package com.zukotuutori.mavenassist.maven;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Looks up the newest version of an artifact via the Maven Central search API. */
public final class MavenCentralClient {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final Pattern LATEST_VERSION = Pattern.compile("\"latestVersion\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern NUM_FOUND = Pattern.compile("\"numFound\"\\s*:\\s*(\\d+)");

    private MavenCentralClient() {
    }

    /**
     * Whether this exact version exists on Maven Central. Returns null when it could not
     * be determined (offline, timeout) — callers should not block the user on that.
     */
    public static @Nullable Boolean versionExists(@NotNull String groupId,
                                                  @NotNull String artifactId,
                                                  @NotNull String version) {
        try {
            String query = URLEncoder.encode(
                    "g:\"" + groupId + "\" AND a:\"" + artifactId + "\" AND v:\"" + version + "\"",
                    StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://search.maven.org/solrsearch/select?q=" + query
                            + "&core=gav&rows=1&wt=json"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }
            Matcher m = NUM_FOUND.matcher(response.body());
            return m.find() ? Long.parseLong(m.group(1)) > 0 : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Returns the newest version known to Maven Central, or null if it could not be determined. */
    public static @Nullable String getLatestVersion(@NotNull String groupId, @NotNull String artifactId) {
        try {
            String query = URLEncoder.encode(
                    "g:\"" + groupId + "\" AND a:\"" + artifactId + "\"", StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://search.maven.org/solrsearch/select?q=" + query + "&rows=1&wt=json"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }
            Matcher m = LATEST_VERSION.matcher(response.body());
            return m.find() ? m.group(1) : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}

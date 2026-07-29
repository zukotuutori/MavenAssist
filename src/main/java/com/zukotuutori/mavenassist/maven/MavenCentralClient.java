package com.zukotuutori.mavenassist.maven;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Looks up the newest version of an artifact via the Maven Central search API. */
public final class MavenCentralClient {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final Pattern VERSION = Pattern.compile("\"v\"\\s*:\\s*\"([^\"]+)\"");
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final ConcurrentHashMap<String, CacheEntry> CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, CompletableFuture<String>> IN_FLIGHT = new ConcurrentHashMap<>();

    private record CacheEntry(@Nullable String version, long expiresAtNanos) {
    }

    /**
     * The characters Maven itself allows in a coordinate. Everything the plugin interpolates
     * into a Solr query is checked against this first: percent-encoding makes the URL safe,
     * but it does not stop a quote inside a coordinate from closing the quoted Solr term and
     * rewriting the query, which could answer with a different artifact's version, and that
     * version then gets written into the user's pom.
     */
    private static final Pattern SAFE_COORDINATE = Pattern.compile("[A-Za-z0-9_.+\\-]{1,200}");

    private MavenCentralClient() {
    }

    private static boolean unsafe(@NotNull String... coordinates) {
        for (String coordinate : coordinates) {
            if (!SAFE_COORDINATE.matcher(coordinate).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the most recently published stable version known to Maven Central.
     * Previews, milestones, release candidates and snapshots are intentionally skipped.
     */
    public static @Nullable String getLatestVersion(@NotNull String groupId, @NotNull String artifactId) {
        if (unsafe(groupId, artifactId)) {
            return null;
        }
        String key = groupId + ":" + artifactId;
        long now = System.nanoTime();
        CacheEntry cached = CACHE.get(key);
        if (cached != null && cached.expiresAtNanos() > now) {
            return cached.version();
        }
        CompletableFuture<String> request = new CompletableFuture<>();
        CompletableFuture<String> existing = IN_FLIGHT.putIfAbsent(key, request);
        if (existing != null) {
            try {
                return existing.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (ExecutionException e) {
                return null;
            }
        }
        try {
            String latest = fetchLatestVersion(groupId, artifactId);
            CACHE.put(key, new CacheEntry(latest,
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(latest == null ? 15 : 120)));
            request.complete(latest);
            return latest;
        } catch (Exception e) {
            request.complete(null);
            return null;
        } finally {
            IN_FLIGHT.remove(key, request);
        }
    }

    private static @Nullable String fetchLatestVersion(@NotNull String groupId,
                                                       @NotNull String artifactId) {
        try {
            String query = URLEncoder.encode(
                    "g:\"" + groupId + "\" AND a:\"" + artifactId + "\"", StandardCharsets.UTF_8);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create("https://search.maven.org/solrsearch/select?q=" + query
                            + "&core=gav&rows=200&fl=v&sort=timestamp%20desc&wt=json"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<InputStream> response =
                    CLIENT.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) {
                    return null;
                }
                byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES) {
                    return null;
                }
                Matcher versions = VERSION.matcher(new String(bytes, StandardCharsets.UTF_8));
                while (versions.find()) {
                    // This string is about to be written into the user's pom, so it has to look
                    // like a version even though it came from a trusted host over TLS.
                    String candidate = versions.group(1);
                    if (!unsafe(candidate) && VersionPolicy.isStable(candidate)) {
                        return candidate;
                    }
                }
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}

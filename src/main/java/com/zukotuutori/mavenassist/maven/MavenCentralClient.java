package com.zukotuutori.mavenassist.maven;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Looks up the newest stable version from Maven Central's repository metadata. */
public final class MavenCentralClient {

    private static final String CENTRAL_REPOSITORY = "https://repo1.maven.org/maven2/";
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final ConcurrentHashMap<String, CacheEntry> CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, CompletableFuture<String>> IN_FLIGHT = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, VersionsCacheEntry> VERSIONS_CACHE = new ConcurrentHashMap<>();

    private record CacheEntry(@Nullable String version, long expiresAtNanos) {
    }

    private record VersionsCacheEntry(@Nullable List<String> versions, long expiresAtNanos) {
    }

    /**
     * Coordinates become path segments in the metadata URL, and returned versions may be
     * written into a pom. Restrict both to Maven's ordinary coordinate character set.
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

    /**
     * Returns every version Maven Central publishes for the artifact, oldest first, or null when
     * the metadata could not be read at all - unknown artifact, private repository or no network.
     * Unlike {@link #getLatestVersion} this keeps previews and snapshots: it answers "does this
     * exact version exist?", and a user may legitimately pin a release candidate.
     */
    public static @Nullable List<String> getAvailableVersions(@NotNull String groupId,
                                                              @NotNull String artifactId) {
        if (unsafe(groupId, artifactId)) {
            return null;
        }
        String key = groupId + ":" + artifactId;
        VersionsCacheEntry cached = VERSIONS_CACHE.get(key);
        if (cached != null && cached.expiresAtNanos() > System.nanoTime()) {
            return cached.versions();
        }
        List<String> versions = null;
        try {
            byte[] metadata = fetchMetadata(groupId, artifactId);
            if (metadata != null) {
                versions = parseVersions(metadata);
            }
        } catch (Exception e) {
            versions = null;
        }
        VERSIONS_CACHE.put(key, new VersionsCacheEntry(versions,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(versions == null ? 15 : 120)));
        return versions;
    }

    private static @Nullable String fetchLatestVersion(@NotNull String groupId,
                                                       @NotNull String artifactId) {
        try {
            byte[] metadata = fetchMetadata(groupId, artifactId);
            return metadata == null ? null : parseLatestVersion(metadata);
        } catch (Exception e) {
            return null;
        }
    }

    /** The artifact's raw {@code maven-metadata.xml}, or null when it cannot be retrieved. */
    private static byte @Nullable [] fetchMetadata(@NotNull String groupId,
                                                   @NotNull String artifactId) {
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(metadataUri(groupId, artifactId))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/xml, text/xml")
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
                return bytes;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    static @NotNull URI metadataUri(@NotNull String groupId, @NotNull String artifactId) {
        if (unsafe(groupId, artifactId) || pathTraversalSegment(artifactId)
                || groupId.startsWith(".") || groupId.endsWith(".") || groupId.contains("..")) {
            throw new IllegalArgumentException("Unsafe Maven coordinate");
        }
        return URI.create(CENTRAL_REPOSITORY + groupId.replace('.', '/') + "/"
                + artifactId + "/maven-metadata.xml");
    }

    /** Parses repository metadata and returns its newest stable release. */
    static @Nullable String parseLatestVersion(byte @NotNull [] metadata) throws IOException {
        Element versioning = parseVersioning(metadata);
        if (versioning == null) {
            return null;
        }
        String release = directChildText(versioning, "release");
        if (usableStableVersion(release)) {
            return release;
        }

        // Some artifacts set <release> to an alpha/beta. The versions list is ordered,
        // so walk backwards to find the newest stable entry instead.
        Element versions = directChild(versioning, "versions");
        if (versions == null) {
            return null;
        }
        for (Node node = versions.getLastChild(); node != null; node = node.getPreviousSibling()) {
            if (node instanceof Element element && "version".equals(element.getTagName())) {
                String candidate = element.getTextContent().trim();
                if (usableStableVersion(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /** Parses repository metadata and returns every listed version in document order. */
    static @NotNull List<String> parseVersions(byte @NotNull [] metadata) throws IOException {
        Element versioning = parseVersioning(metadata);
        Element versions = versioning == null ? null : directChild(versioning, "versions");
        if (versions == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Node node = versions.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && "version".equals(element.getTagName())) {
                String version = element.getTextContent().trim();
                if (!version.isEmpty()) {
                    result.add(version);
                }
            }
        }
        return result;
    }

    /** The {@code <versioning>} element of repository metadata, or null when it is not one. */
    private static @Nullable Element parseVersioning(byte @NotNull [] metadata) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler());
            Document document = builder.parse(new ByteArrayInputStream(metadata));
            Element root = document.getDocumentElement();
            if (root == null || !"metadata".equals(root.getTagName())) {
                return null;
            }
            return directChild(root, "versioning");
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Invalid Maven metadata", e);
        }
    }

    private static boolean usableStableVersion(@Nullable String version) {
        return version != null && !version.isBlank() && !unsafe(version)
                && !pathTraversalSegment(version) && VersionPolicy.isStable(version);
    }

    private static boolean pathTraversalSegment(@NotNull String value) {
        return ".".equals(value) || "..".equals(value);
    }

    private static @Nullable Element directChild(@NotNull Element parent, @NotNull String name) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(element.getTagName())) {
                return element;
            }
        }
        return null;
    }

    private static @Nullable String directChildText(@NotNull Element parent, @NotNull String name) {
        Element child = directChild(parent, name);
        return child == null ? null : child.getTextContent().trim();
    }
}

package com.zukotuutori.mavenassist.maven;

import com.zukotuutori.mavenassist.model.Dependency;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads pom.xml files via DOM and edits them with targeted text replacements,
 * so the existing formatting of the file is preserved.
 */
public final class PomUtils {

    public record PomInfo(String groupId, String artifactId, String version) {
    }

    private PomUtils() {
    }

    // ---------------------------------------------------------------- reading

    /** Returns the direct dependencies of the pom, with ${property} versions resolved when possible. */
    public static @NotNull List<Dependency> readDependencies(@NotNull File pomFile) throws IOException {
        Element project = parse(pomFile);
        Map<String, String> properties = readProperties(project);
        List<Dependency> result = new ArrayList<>();
        Element dependencies = childElement(project, "dependencies");
        if (dependencies == null) {
            return result;
        }
        for (Element dep : childElements(dependencies, "dependency")) {
            String groupId = childText(dep, "groupId");
            String artifactId = childText(dep, "artifactId");
            String version = childText(dep, "version");
            if (version != null && version.startsWith("${") && version.endsWith("}")) {
                version = properties.getOrDefault(version.substring(2, version.length() - 1), version);
            }
            if (groupId != null && artifactId != null) {
                result.add(new Dependency(groupId, artifactId, version));
            }
        }
        return result;
    }

    /** Reads groupId/artifactId/version of the pom itself (inheriting missing values from its parent block). */
    public static @NotNull PomInfo readPomInfo(@NotNull File pomFile) throws IOException {
        Element project = parse(pomFile);
        Element parent = childElement(project, "parent");
        String groupId = childText(project, "groupId");
        String version = childText(project, "version");
        if (parent != null) {
            if (groupId == null) groupId = childText(parent, "groupId");
            if (version == null) version = childText(parent, "version");
        }
        String artifactId = childText(project, "artifactId");
        if (groupId == null || artifactId == null || version == null) {
            throw new IOException("Could not read groupId/artifactId/version from " + pomFile);
        }
        return new PomInfo(groupId, artifactId, version);
    }

    /** Reads the &lt;parent&gt; block of the pom, or null if it has none. */
    public static @Nullable PomInfo readParentInfo(@NotNull File pomFile) throws IOException {
        Element project = parse(pomFile);
        Element parent = childElement(project, "parent");
        if (parent == null) {
            return null;
        }
        String groupId = childText(parent, "groupId");
        String artifactId = childText(parent, "artifactId");
        String version = childText(parent, "version");
        return new PomInfo(groupId == null ? "" : groupId,
                artifactId == null ? "" : artifactId,
                version == null ? "" : version);
    }

    /**
     * Resolves the parent pom's file via &lt;relativePath&gt; (or the Maven default
     * {@code ../pom.xml} when the tag is absent), or null if the pom has no parent,
     * the tag is explicitly empty, or the file does not exist.
     */
    public static @Nullable File readParentPomFile(@NotNull File pomFile) throws IOException {
        Element project = parse(pomFile);
        Element parent = childElement(project, "parent");
        if (parent == null) {
            return null;
        }
        String relativePath = childText(parent, "relativePath");
        if (relativePath == null) {
            relativePath = "../pom.xml";
        } else if (relativePath.isEmpty()) {
            return null; // <relativePath/> = "resolve from the repository, not from disk"
        }
        File candidate = new File(pomFile.getParentFile(), relativePath);
        if (candidate.isDirectory()) {
            candidate = new File(candidate, "pom.xml");
        }
        try {
            candidate = candidate.getCanonicalFile();
        } catch (IOException ignored) {
            // keep the non-canonical form
        }
        return candidate.isFile() ? candidate : null;
    }

    /**
     * True when the pom's &lt;parent&gt; already points at exactly this file and the
     * declared coordinates still match the parent's — re-selecting it changes nothing.
     */
    public static boolean parentAlreadySet(@NotNull File pomFile, @NotNull File parentFile) throws IOException {
        File current = readParentPomFile(pomFile);
        if (current == null) {
            return false;
        }
        if (!current.getCanonicalFile().equals(parentFile.getCanonicalFile())) {
            return false;
        }
        PomInfo declared = readParentInfo(pomFile);
        PomInfo actual = readPomInfo(parentFile);
        return declared != null
                && declared.groupId().equals(actual.groupId())
                && declared.artifactId().equals(actual.artifactId())
                && declared.version().equals(actual.version());
    }

    /** The pom's own &lt;packaging&gt;, or "jar" (Maven's default) if the tag is absent. */
    public static @NotNull String readPackaging(@NotNull File pomFile) throws IOException {
        String packaging = childText(parse(pomFile), "packaging");
        return packaging == null || packaging.isEmpty() ? "jar" : packaging;
    }

    // ---------------------------------------------------------------- editing

    /**
     * Sets the pom's &lt;packaging&gt; to "pom" — Maven requires that of every parent pom.
     * The rest of the file keeps its formatting. Returns false if it already was "pom".
     */
    public static boolean ensurePomPackaging(@NotNull File pomFile) throws IOException {
        if ("pom".equals(readPackaging(pomFile))) {
            return false;
        }
        Path path = pomFile.toPath();
        String content = Files.readString(path, StandardCharsets.UTF_8);

        List<int[]> excluded = excludedRegions(content);
        Matcher parentBlock = Pattern.compile("<parent>.*?</parent>", Pattern.DOTALL).matcher(content);
        if (parentBlock.find()) {
            excluded.add(new int[]{parentBlock.start(), parentBlock.end()});
        }
        Matcher tag = Pattern.compile("<packaging>\\s*[^<]*\\s*</packaging>").matcher(content);
        while (tag.find()) {
            if (inExcludedRegion(excluded, tag.start())) {
                continue;
            }
            Files.writeString(path, content.substring(0, tag.start())
                    + "<packaging>pom</packaging>" + content.substring(tag.end()), StandardCharsets.UTF_8);
            return true;
        }
        // No tag yet (Maven defaulted to jar): insert one, like setParentPom does.
        Matcher modelVersion = Pattern.compile("<modelVersion>.*?</modelVersion>", Pattern.DOTALL).matcher(content);
        if (modelVersion.find()) {
            content = content.substring(0, modelVersion.end())
                    + "\n    <packaging>pom</packaging>" + content.substring(modelVersion.end());
        } else {
            int projectTag = content.indexOf("<project");
            int open = projectTag < 0 ? -1 : content.indexOf('>', projectTag);
            if (open < 0) {
                throw new IOException("Invalid pom.xml: no <project> tag found");
            }
            content = content.substring(0, open + 1)
                    + "\n    <packaging>pom</packaging>" + content.substring(open + 1);
        }
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return true;
    }

    /** Sets or replaces the &lt;parent&gt; block of the pom; the rest of the file keeps its formatting. */
    public static void setParentPom(@NotNull File pomFile,
                                    @NotNull PomInfo parent,
                                    @Nullable File parentPomFile) throws IOException {
        Path path = pomFile.toPath();
        String content = Files.readString(path, StandardCharsets.UTF_8);

        StringBuilder sb = new StringBuilder();
        sb.append("<parent>\n");
        sb.append("        <groupId>").append(escapeXml(parent.groupId())).append("</groupId>\n");
        sb.append("        <artifactId>").append(escapeXml(parent.artifactId())).append("</artifactId>\n");
        sb.append("        <version>").append(escapeXml(parent.version())).append("</version>\n");
        if (parentPomFile != null) {
            sb.append("        <relativePath>")
                    .append(escapeXml(relativize(pomFile.getParentFile(), parentPomFile)))
                    .append("</relativePath>\n");
        }
        sb.append("    </parent>");
        String block = sb.toString();

        Matcher existing = Pattern.compile("<parent>.*?</parent>", Pattern.DOTALL).matcher(content);
        if (existing.find()) {
            content = content.substring(0, existing.start()) + block + content.substring(existing.end());
        } else {
            Matcher modelVersion = Pattern.compile("<modelVersion>.*?</modelVersion>", Pattern.DOTALL).matcher(content);
            if (modelVersion.find()) {
                content = content.substring(0, modelVersion.end())
                        + "\n\n    " + block + content.substring(modelVersion.end());
            } else {
                int projectTag = content.indexOf("<project");
                int open = projectTag < 0 ? -1 : content.indexOf('>', projectTag);
                if (open < 0) {
                    throw new IOException("Invalid pom.xml: no <project> tag found");
                }
                content = content.substring(0, open + 1) + "\n    " + block + content.substring(open + 1);
            }
        }
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    /**
     * Sets the version of the given dependency. If the dependency references a
     * ${property}, the property value is updated instead. If the dependency has
     * no version tag, one is inserted.
     */
    public static void setDependencyVersion(@NotNull File pomFile,
                                            @NotNull String groupId,
                                            @NotNull String artifactId,
                                            @NotNull String newVersion) throws IOException {
        Path path = pomFile.toPath();
        String content = Files.readString(path, StandardCharsets.UTF_8);

        List<int[]> excluded = excludedRegions(content);
        Matcher block = Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL).matcher(content);
        while (block.find()) {
            if (inExcludedRegion(excluded, block.start())) {
                continue; // dependencyManagement / profiles declare, they are not the project's dependencies
            }
            String body = block.group(1);
            if (!containsTag(body, "groupId", groupId) || !containsTag(body, "artifactId", artifactId)) {
                continue;
            }
            Matcher versionTag = Pattern.compile("<version>\\s*(.*?)\\s*</version>", Pattern.DOTALL).matcher(body);
            String newBody;
            if (versionTag.find()) {
                String raw = versionTag.group(1);
                if (raw.startsWith("${") && raw.endsWith("}")) {
                    String property = raw.substring(2, raw.length() - 1);
                    String updated = replacePropertyValue(content, property, newVersion);
                    if (updated != null) {
                        Files.writeString(path, updated, StandardCharsets.UTF_8);
                        return;
                    }
                }
                newBody = body.substring(0, versionTag.start())
                        + "<version>" + escapeXml(newVersion) + "</version>"
                        + body.substring(versionTag.end());
            } else {
                int idx = body.indexOf("</artifactId>");
                if (idx < 0) {
                    continue;
                }
                int end = idx + "</artifactId>".length();
                newBody = body.substring(0, end)
                        + "\n            <version>" + escapeXml(newVersion) + "</version>"
                        + body.substring(end);
            }
            String updatedContent = content.substring(0, block.start(1)) + newBody + content.substring(block.end(1));
            Files.writeString(path, updatedContent, StandardCharsets.UTF_8);
            return;
        }
        throw new IOException("Dependency " + groupId + ":" + artifactId + " not found in " + pomFile.getName());
    }

    /** Removes the dependency's whole &lt;dependency&gt; block; the rest of the file keeps its formatting. */
    public static void removeDependency(@NotNull File pomFile,
                                        @NotNull String groupId,
                                        @NotNull String artifactId) throws IOException {
        Path path = pomFile.toPath();
        String content = Files.readString(path, StandardCharsets.UTF_8);

        List<int[]> excluded = excludedRegions(content);
        Matcher block = Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL).matcher(content);
        while (block.find()) {
            if (inExcludedRegion(excluded, block.start())) {
                continue; // dependencyManagement / profiles declare, they are not the project's dependencies
            }
            String body = block.group(1);
            if (!containsTag(body, "groupId", groupId) || !containsTag(body, "artifactId", artifactId)) {
                continue;
            }
            int start = block.start();
            // Swallow the indentation and line break before the block so no empty line remains.
            while (start > 0 && (content.charAt(start - 1) == ' ' || content.charAt(start - 1) == '\t')) {
                start--;
            }
            if (start > 0 && content.charAt(start - 1) == '\n') {
                start--;
            }
            Files.writeString(path, content.substring(0, start) + content.substring(block.end()),
                    StandardCharsets.UTF_8);
            return;
        }
        throw new IOException("Dependency " + groupId + ":" + artifactId + " not found in " + pomFile.getName());
    }

    /** Appends the given dependencies to the top-level &lt;dependencies&gt; section (creating it if needed). */
    public static void addDependencies(@NotNull File pomFile, @NotNull List<Dependency> dependencies) throws IOException {
        if (dependencies.isEmpty()) {
            return;
        }
        Path path = pomFile.toPath();
        String content = Files.readString(path, StandardCharsets.UTF_8);

        StringBuilder snippet = new StringBuilder();
        for (Dependency d : dependencies) {
            snippet.append(dependencyXml(d, "        "));
        }

        int insertAt = findTopLevelDependenciesClose(content);
        if (insertAt >= 0) {
            // Insert before the closing tag's line so its indentation is preserved.
            int lineStart = content.lastIndexOf('\n', insertAt) + 1;
            if (content.substring(lineStart, insertAt).isBlank()) {
                insertAt = lineStart;
            }
            content = content.substring(0, insertAt) + snippet + content.substring(insertAt);
        } else {
            int projectClose = content.lastIndexOf("</project>");
            if (projectClose < 0) {
                throw new IOException("Invalid pom.xml: no </project> tag found");
            }
            content = content.substring(0, projectClose)
                    + "    <dependencies>\n" + snippet + "    </dependencies>\n\n"
                    + content.substring(projectClose);
        }
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    /** Creates a brand-new pom.xml. {@code parent} and {@code parentPomFile} may be null. */
    public static void createPom(@NotNull File targetPomFile,
                                 @NotNull String groupId,
                                 @NotNull String artifactId,
                                 @NotNull String version,
                                 @Nullable PomInfo parent,
                                 @Nullable File parentPomFile,
                                 @NotNull List<Dependency> dependencies) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n");
        sb.append("         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
        sb.append("         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd\">\n");
        sb.append("    <modelVersion>4.0.0</modelVersion>\n\n");

        if (parent != null) {
            sb.append("    <parent>\n");
            sb.append("        <groupId>").append(escapeXml(parent.groupId())).append("</groupId>\n");
            sb.append("        <artifactId>").append(escapeXml(parent.artifactId())).append("</artifactId>\n");
            sb.append("        <version>").append(escapeXml(parent.version())).append("</version>\n");
            if (parentPomFile != null) {
                String relativePath = relativize(targetPomFile.getParentFile(), parentPomFile);
                sb.append("        <relativePath>").append(escapeXml(relativePath)).append("</relativePath>\n");
            }
            sb.append("    </parent>\n\n");
        }

        sb.append("    <groupId>").append(escapeXml(groupId)).append("</groupId>\n");
        sb.append("    <artifactId>").append(escapeXml(artifactId)).append("</artifactId>\n");
        sb.append("    <version>").append(escapeXml(version)).append("</version>\n");
        sb.append("    <packaging>jar</packaging>\n\n");

        sb.append("    <properties>\n");
        sb.append("        <maven.compiler.source>17</maven.compiler.source>\n");
        sb.append("        <maven.compiler.target>17</maven.compiler.target>\n");
        sb.append("        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>\n");
        sb.append("    </properties>\n");

        if (!dependencies.isEmpty()) {
            sb.append("\n    <dependencies>\n");
            for (Dependency d : dependencies) {
                sb.append(dependencyXml(d, "        "));
            }
            sb.append("    </dependencies>\n");
        }

        sb.append("</project>\n");
        Files.writeString(targetPomFile.toPath(), sb.toString(), StandardCharsets.UTF_8);
    }

    /**
     * Parses one or more &lt;dependency&gt; blocks (or plain "groupId:artifactId[:version]" lines)
     * from pasted text. Never throws; returns everything it could parse.
     */
    public static @NotNull List<Dependency> parsePastedDependencies(@NotNull String text) {
        List<Dependency> result = new ArrayList<>();
        Matcher block = Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL).matcher(text);
        boolean foundXml = false;
        while (block.find()) {
            foundXml = true;
            String body = block.group(1);
            String groupId = firstTagValue(body, "groupId");
            String artifactId = firstTagValue(body, "artifactId");
            String version = firstTagValue(body, "version");
            if (groupId != null && artifactId != null) {
                if (version != null && version.startsWith("${")) {
                    version = "";
                }
                result.add(new Dependency(groupId, artifactId, version));
            }
        }
        if (!foundXml) {
            for (String line : text.split("\\R")) {
                Dependency d = Dependency.fromGav(line);
                if (d != null) {
                    result.add(d);
                }
            }
        }
        return result;
    }

    // ---------------------------------------------------------------- helpers

    private static @NotNull String dependencyXml(@NotNull Dependency d, @NotNull String indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent).append("<dependency>\n");
        sb.append(indent).append("    <groupId>").append(escapeXml(d.getGroupId())).append("</groupId>\n");
        sb.append(indent).append("    <artifactId>").append(escapeXml(d.getArtifactId())).append("</artifactId>\n");
        if (!d.getVersion().isEmpty()) {
            sb.append(indent).append("    <version>").append(escapeXml(d.getVersion())).append("</version>\n");
        }
        sb.append(indent).append("</dependency>\n");
        return sb.toString();
    }

    /**
     * Character ranges of the blocks that declare dependencies without adding them to the
     * project: {@code <dependencyManagement>} and {@code <profiles>}. Edits must skip these.
     */
    private static @NotNull List<int[]> excludedRegions(@NotNull String content) {
        List<int[]> regions = new ArrayList<>();
        for (String tag : new String[]{"dependencyManagement", "profiles"}) {
            Matcher m = Pattern.compile("<" + tag + ">.*?</" + tag + ">", Pattern.DOTALL).matcher(content);
            while (m.find()) {
                regions.add(new int[]{m.start(), m.end()});
            }
        }
        return regions;
    }

    private static boolean inExcludedRegion(@NotNull List<int[]> regions, int index) {
        for (int[] region : regions) {
            if (index >= region[0] && index < region[1]) {
                return true;
            }
        }
        return false;
    }

    /** Index of the top-level {@code </dependencies>} tag, or -1. */
    private static int findTopLevelDependenciesClose(@NotNull String content) {
        List<int[]> excluded = excludedRegions(content);
        int searchFrom = 0;
        while (true) {
            int close = content.indexOf("</dependencies>", searchFrom);
            if (close < 0) {
                return -1;
            }
            if (inExcludedRegion(excluded, close)) {
                searchFrom = close + 1;
                continue;
            }
            return close;
        }
    }

    private static boolean containsTag(@NotNull String body, @NotNull String tag, @NotNull String value) {
        Pattern p = Pattern.compile("<" + tag + ">\\s*" + Pattern.quote(escapeXml(value)) + "\\s*</" + tag + ">");
        return p.matcher(body).find();
    }

    private static @Nullable String firstTagValue(@NotNull String body, @NotNull String tag) {
        Matcher m = Pattern.compile("<" + tag + ">\\s*(.*?)\\s*</" + tag + ">", Pattern.DOTALL).matcher(body);
        return m.find() ? unescapeXml(m.group(1)) : null;
    }

    private static @Nullable String replacePropertyValue(@NotNull String content,
                                                         @NotNull String property,
                                                         @NotNull String newValue) {
        List<int[]> excluded = excludedRegions(content);
        Pattern p = Pattern.compile("<" + Pattern.quote(property) + ">\\s*[^<]*\\s*</" + Pattern.quote(property) + ">");
        Matcher m = p.matcher(content);
        while (m.find()) {
            if (inExcludedRegion(excluded, m.start())) {
                continue; // a profile's <properties> does not define the active version
            }
            return content.substring(0, m.start())
                    + "<" + property + ">" + escapeXml(newValue) + "</" + property + ">"
                    + content.substring(m.end());
        }
        return null;
    }

    /**
     * Escapes text before it is written into an element body. Coordinates normally contain no
     * special characters, but pasted snippets (see {@link #parsePastedDependencies}) are arbitrary
     * text and would otherwise produce an unparseable pom.
     */
    private static @NotNull String escapeXml(@NotNull String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    /** Inverse of {@link #escapeXml} for values read out of raw text rather than through the DOM. */
    private static @NotNull String unescapeXml(@NotNull String text) {
        return text.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    private static @NotNull String relativize(@NotNull File fromDir, @NotNull File toFile) {
        try {
            return fromDir.toPath().toAbsolutePath().normalize()
                    .relativize(toFile.toPath().toAbsolutePath().normalize())
                    .toString().replace(File.separatorChar, '/');
        } catch (IllegalArgumentException e) {
            return toFile.getAbsolutePath().replace(File.separatorChar, '/');
        }
    }

    private static @NotNull Element parse(@NotNull File pomFile) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            // Forbid DOCTYPE entirely: a real pom never has one, and this closes XXE and
            // entity-expansion ("billion laughs") for good, independent of the JDK defaults.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(false);
            Document doc = factory.newDocumentBuilder().parse(pomFile);
            return doc.getDocumentElement();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to parse " + pomFile.getName() + ": " + e.getMessage(), e);
        }
    }

    private static @NotNull Map<String, String> readProperties(@NotNull Element project) {
        Map<String, String> result = new HashMap<>();
        Element properties = childElement(project, "properties");
        if (properties != null) {
            NodeList children = properties.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node node = children.item(i);
                if (node instanceof Element el) {
                    result.put(el.getTagName(), el.getTextContent().trim());
                }
            }
        }
        return result;
    }

    private static @Nullable Element childElement(@NotNull Element parent, @NotNull String name) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element el && el.getTagName().equals(name)) {
                return el;
            }
        }
        return null;
    }

    private static @NotNull List<Element> childElements(@NotNull Element parent, @NotNull String name) {
        List<Element> result = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element el && el.getTagName().equals(name)) {
                result.add(el);
            }
        }
        return result;
    }

    private static @Nullable String childText(@NotNull Element parent, @NotNull String name) {
        Element el = childElement(parent, name);
        return el == null ? null : el.getTextContent().trim();
    }
}

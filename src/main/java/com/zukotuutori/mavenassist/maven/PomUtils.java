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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
            String type = childText(dep, "type");
            String classifier = childText(dep, "classifier");
            if (version != null && version.startsWith("${") && version.endsWith("}")) {
                version = properties.getOrDefault(version.substring(2, version.length() - 1), version);
            }
            if (groupId != null && artifactId != null) {
                result.add(new Dependency(groupId, artifactId, version, null, type, classifier));
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
     * declared coordinates still match the parent's, so re-selecting it changes nothing.
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
     * Sets the pom's &lt;packaging&gt; to "pom". Maven requires that of every parent pom.
     * The rest of the file keeps its formatting. Returns false if it already was "pom".
     */
    public static synchronized boolean ensurePomPackaging(@NotNull File pomFile) throws IOException {
        if ("pom".equals(readPackaging(pomFile))) {
            return false;
        }
        Path path = pomFile.toPath();
        String content = Files.readString(path, StandardCharsets.UTF_8);
        String searchable = maskIgnoredMarkup(content);

        List<int[]> excluded = excludedRegions(content);
        Matcher parentBlock = elementBlockPattern("parent").matcher(searchable);
        if (findOutside(parentBlock, excluded)) {
            excluded.add(new int[]{parentBlock.start(), parentBlock.end()});
        }
        Matcher tag = elementValuePattern("packaging").matcher(searchable);
        while (tag.find()) {
            if (inExcludedRegion(excluded, tag.start())) {
                continue;
            }
            writeAtomically(path, replaceElementBody(content, tag, "pom"));
            return true;
        }
        // No tag yet (Maven defaulted to jar): insert one, like setParentPom does.
        Matcher modelVersion = elementBlockPattern("modelVersion").matcher(searchable);
        if (modelVersion.find()) {
            String prefix = elementPrefixAt(searchable, "modelVersion", modelVersion.start());
            content = content.substring(0, modelVersion.end())
                    + "\n    <" + prefix + "packaging>pom</" + prefix + "packaging>"
                    + content.substring(modelVersion.end());
        } else {
            Matcher projectTag = openTagWithPrefixPattern("project").matcher(searchable);
            if (!projectTag.find()) {
                throw new IOException("Invalid pom.xml: no <project> tag found");
            }
            String prefix = projectTag.group(1) == null ? "" : projectTag.group(1);
            content = content.substring(0, projectTag.end())
                    + "\n    <" + prefix + "packaging>pom</" + prefix + "packaging>"
                    + content.substring(projectTag.end());
        }
        writeAtomically(path, content);
        return true;
    }

    /** Sets or replaces the &lt;parent&gt; block of the pom; the rest of the file keeps its formatting. */
    public static synchronized void setParentPom(@NotNull File pomFile,
                                                 @NotNull PomInfo parent,
                                                 @Nullable File parentPomFile) throws IOException {
        Path path = pomFile.toPath();
        String content = Files.readString(path, StandardCharsets.UTF_8);
        String searchable = maskIgnoredMarkup(content);
        List<int[]> excluded = excludedRegions(content);

        Matcher existing = elementBlockPattern("parent").matcher(searchable);
        if (findOutside(existing, excluded)) {
            String prefix = elementPrefixAt(searchable, "parent", existing.start());
            String block = parentXml(parent, parentPomFile, pomFile.getParentFile(), prefix);
            content = content.substring(0, existing.start()) + block + content.substring(existing.end());
        } else {
            Matcher modelVersion = elementBlockPattern("modelVersion").matcher(searchable);
            if (modelVersion.find()) {
                String prefix = elementPrefixAt(searchable, "modelVersion", modelVersion.start());
                String block = parentXml(parent, parentPomFile, pomFile.getParentFile(), prefix);
                content = content.substring(0, modelVersion.end())
                        + "\n\n    " + block + content.substring(modelVersion.end());
            } else {
                Matcher projectTag = openTagWithPrefixPattern("project").matcher(searchable);
                if (!projectTag.find()) {
                    throw new IOException("Invalid pom.xml: no <project> tag found");
                }
                String prefix = projectTag.group(1) == null ? "" : projectTag.group(1);
                String block = parentXml(parent, parentPomFile, pomFile.getParentFile(), prefix);
                content = content.substring(0, projectTag.end())
                        + "\n    " + block + content.substring(projectTag.end());
            }
        }
        writeAtomically(path, content);
    }

    /**
     * Sets the version of the given dependency. If the dependency references a
     * ${property}, the property value is updated instead. If the dependency has
     * no version tag, one is inserted.
     */
    public static synchronized void setDependencyVersion(@NotNull File pomFile,
                                                         @NotNull String groupId,
                                                         @NotNull String artifactId,
                                                         @NotNull String newVersion) throws IOException {
        setDependencyVersionInternal(pomFile, new Dependency(groupId, artifactId, null), newVersion, false);
    }

    /** Sets exactly this dependency, including its Maven type and classifier identity. */
    public static synchronized void setDependencyVersion(@NotNull File pomFile,
                                                         @NotNull Dependency dependency,
                                                         @NotNull String newVersion) throws IOException {
        setDependencyVersionInternal(pomFile, dependency, newVersion, true);
    }

    private static void setDependencyVersionInternal(@NotNull File pomFile,
                                                     @NotNull Dependency dependency,
                                                     @NotNull String newVersion,
                                                     boolean verifyExpectedVersion) throws IOException {
        if (verifyExpectedVersion) {
            Dependency current = readDependencies(pomFile).stream()
                    .filter(candidate -> candidate.getKey().equals(dependency.getKey()))
                    .findFirst().orElse(null);
            if (current == null) {
                throw new IOException("Dependency " + dependency.getKey() + " is no longer in " + pomFile.getName());
            }
            if (!current.getVersion().equals(dependency.getVersion())) {
                throw new IOException("Dependency " + dependency.getKey() + " changed on disk from "
                        + dependency.getVersion() + " to " + current.getVersion() + "; reload and try again.");
            }
        }
        Path path = pomFile.toPath();
        String content = Files.readString(path, StandardCharsets.UTF_8);
        String searchable = maskIgnoredMarkup(content);

        List<int[]> excluded = excludedRegions(content);
        Matcher block = elementBlockPattern("dependency").matcher(searchable);
        while (block.find()) {
            if (inExcludedRegion(excluded, block.start())) {
                continue; // dependencyManagement / profiles declare, they are not the project's dependencies
            }
            String body = content.substring(block.start(1), block.end(1));
            String searchableBody = searchable.substring(block.start(1), block.end(1));
            if (!matchesDependency(searchableBody, dependency)) {
                continue;
            }
            Matcher versionTag = elementBlockPattern("version").matcher(searchableBody);
            String newBody;
            if (versionTag.find()) {
                String raw = body.substring(versionTag.start(1), versionTag.end(1)).trim();
                if (raw.startsWith("${") && raw.endsWith("}")) {
                    String property = raw.substring(2, raw.length() - 1);
                    // A property can intentionally control several dependencies. Updating it
                    // would make a one-row action silently rewrite all of them, so preserve the
                    // property only when this dependency is its sole direct consumer.
                    String updated = countPropertyReferences(content, property) == 1
                            ? replacePropertyValue(content, property, newVersion) : null;
                    if (updated != null) {
                        writeAtomically(path, updated);
                        return;
                    }
                }
                newBody = replaceElementBody(body, versionTag, newVersion);
            } else {
                Matcher artifactClose = closeTagWithPrefixPattern("artifactId").matcher(searchableBody);
                if (!artifactClose.find()) {
                    continue;
                }
                int end = artifactClose.end();
                String prefix = artifactClose.group(1) == null ? "" : artifactClose.group(1);
                newBody = body.substring(0, end)
                        + "\n            <" + prefix + "version>" + escapeXml(newVersion)
                        + "</" + prefix + "version>"
                        + body.substring(end);
            }
            String updatedContent = content.substring(0, block.start(1)) + newBody + content.substring(block.end(1));
            writeAtomically(path, updatedContent);
            return;
        }
        throw new IOException("Dependency " + dependency.getKey() + " not found in " + pomFile.getName());
    }

    /** Removes the dependency's whole &lt;dependency&gt; block; the rest of the file keeps its formatting. */
    public static void removeDependency(@NotNull File pomFile,
                                        @NotNull String groupId,
                                        @NotNull String artifactId) throws IOException {
        removeDependency(pomFile, new Dependency(groupId, artifactId, null));
    }

    /** Removes exactly this dependency, including its Maven type and classifier identity. */
    public static synchronized void removeDependency(@NotNull File pomFile,
                                                     @NotNull Dependency dependency) throws IOException {
        Path path = pomFile.toPath();
        String content = Files.readString(path, StandardCharsets.UTF_8);
        String searchable = maskIgnoredMarkup(content);

        List<int[]> excluded = excludedRegions(content);
        Matcher block = elementBlockPattern("dependency").matcher(searchable);
        while (block.find()) {
            if (inExcludedRegion(excluded, block.start())) {
                continue; // dependencyManagement / profiles declare, they are not the project's dependencies
            }
            String searchableBody = searchable.substring(block.start(1), block.end(1));
            if (!matchesDependency(searchableBody, dependency)) {
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
            writeAtomically(path, content.substring(0, start) + content.substring(block.end()));
            return;
        }
        throw new IOException("Dependency " + dependency.getKey() + " not found in " + pomFile.getName());
    }

    /** Appends the given dependencies to the top-level &lt;dependencies&gt; section (creating it if needed). */
    public static synchronized void addDependencies(@NotNull File pomFile,
                                                    @NotNull List<Dependency> dependencies) throws IOException {
        if (dependencies.isEmpty()) {
            return;
        }
        Set<String> present = new java.util.HashSet<>();
        for (Dependency existing : readDependencies(pomFile)) {
            present.add(existing.getKey());
        }
        for (Dependency dependency : dependencies) {
            if (!present.add(dependency.getKey())) {
                throw new IOException("Dependency " + dependency.getKey() + " is already in " + pomFile.getName());
            }
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
            int projectClose = lastMatchStart(closeTagPattern("project"), maskIgnoredMarkup(content));
            if (projectClose < 0) {
                throw new IOException("Invalid pom.xml: no </project> tag found");
            }
            content = content.substring(0, projectClose)
                    + "    <dependencies>\n" + snippet + "    </dependencies>\n\n"
                    + content.substring(projectClose);
        }
        writeAtomically(path, content);
    }

    /** Creates a brand-new pom.xml. {@code parent} and {@code parentPomFile} may be null. */
    public static synchronized void createPom(@NotNull File targetPomFile,
                                              @NotNull String groupId,
                                              @NotNull String artifactId,
                                              @NotNull String version,
                                              @Nullable PomInfo parent,
                                              @Nullable File parentPomFile,
                                              @NotNull List<Dependency> dependencies) throws IOException {
        writeNewAtomically(targetPomFile.toPath(), pomXml(targetPomFile, groupId, artifactId,
                version, parent, parentPomFile, dependencies));
    }

    /** Copies the current pom exactly to an explicitly selected export target. */
    public static synchronized void copyPom(@NotNull File sourcePom,
                                            @NotNull File targetPom) throws IOException {
        Path source = sourcePom.toPath().toAbsolutePath().normalize();
        Path target = targetPom.toPath().toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) {
            throw new IOException("Current pom.xml is not a regular file: " + source);
        }
        if (Files.exists(target) && Files.isSameFile(source, target)) {
            throw new IOException("The selected folder already contains the current pom.xml.");
        }
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new IOException("Export directory does not exist: " + target);
        }
        if (Files.exists(target) && !Files.isRegularFile(target)) {
            throw new IOException("Export target is not a regular file: " + target);
        }
        Path temp = Files.createTempFile(parent, ".pom.xml.", ".mavenassist.tmp");
        try {
            Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static @NotNull String pomXml(@NotNull File targetPomFile,
                                          @NotNull String groupId,
                                          @NotNull String artifactId,
                                          @NotNull String version,
                                          @Nullable PomInfo parent,
                                          @Nullable File parentPomFile,
                                          @NotNull List<Dependency> dependencies) {
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
        return sb.toString();
    }

    /**
     * Parses one or more &lt;dependency&gt; blocks (or plain "groupId:artifactId[:version]" lines)
     * from pasted text. Never throws; returns everything it could parse.
     */
    public static @NotNull List<Dependency> parsePastedDependencies(@NotNull String text) {
        List<Dependency> result = new ArrayList<>();
        String searchable = maskIgnoredMarkup(text);
        Matcher block = elementBlockPattern("dependency").matcher(searchable);
        boolean foundXml = false;
        while (block.find()) {
            foundXml = true;
            String body = searchable.substring(block.start(1), block.end(1));
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

    private static final String OPTIONAL_XML_PREFIX = "(?:[A-Za-z_][A-Za-z0-9_.-]*:)?";
    private static final Pattern IGNORED_MARKUP =
            Pattern.compile("<!--.*?-->|<!\\[CDATA\\[.*?]]>", Pattern.DOTALL);

    private static @NotNull String openTagRegex(@NotNull String tag) {
        return "<" + OPTIONAL_XML_PREFIX + Pattern.quote(tag) + "(?=\\s|>)\\s*[^>]*>";
    }

    private static @NotNull String closeTagRegex(@NotNull String tag) {
        return "</" + OPTIONAL_XML_PREFIX + Pattern.quote(tag) + "\\s*>";
    }

    /** Capturing group 1 is the element body. */
    private static @NotNull Pattern elementBlockPattern(@NotNull String tag) {
        return Pattern.compile(openTagRegex(tag) + "(.*?)" + closeTagRegex(tag), Pattern.DOTALL);
    }

    private static @NotNull Pattern elementValuePattern(@NotNull String tag) {
        return elementBlockPattern(tag);
    }

    private static @NotNull Pattern closeTagPattern(@NotNull String tag) {
        return Pattern.compile(closeTagRegex(tag));
    }

    private static @NotNull Pattern closeTagWithPrefixPattern(@NotNull String tag) {
        return Pattern.compile("</(" + OPTIONAL_XML_PREFIX + ")" + Pattern.quote(tag) + "\\s*>");
    }

    private static @NotNull Pattern openTagWithPrefixPattern(@NotNull String tag) {
        return Pattern.compile("<(" + OPTIONAL_XML_PREFIX + ")" + Pattern.quote(tag)
                + "(?=\\s|>)\\s*[^>]*>");
    }

    private static @NotNull String elementPrefixAt(@NotNull String searchable,
                                                   @NotNull String tag,
                                                   int start) {
        Matcher matcher = openTagWithPrefixPattern(tag).matcher(searchable);
        matcher.region(start, searchable.length());
        if (matcher.lookingAt()) {
            return matcher.group(1) == null ? "" : matcher.group(1);
        }
        return "";
    }

    private static int lastMatchStart(@NotNull Pattern pattern, @NotNull String content) {
        Matcher matcher = pattern.matcher(content);
        int result = -1;
        while (matcher.find()) {
            result = matcher.start();
        }
        return result;
    }

    private static @NotNull String parentXml(@NotNull PomInfo parent,
                                             @Nullable File parentPomFile,
                                             @NotNull File childDirectory,
                                             @NotNull String prefix) {
        StringBuilder sb = new StringBuilder();
        sb.append("<").append(prefix).append("parent>\n");
        sb.append("        <").append(prefix).append("groupId>")
                .append(escapeXml(parent.groupId()))
                .append("</").append(prefix).append("groupId>\n");
        sb.append("        <").append(prefix).append("artifactId>")
                .append(escapeXml(parent.artifactId()))
                .append("</").append(prefix).append("artifactId>\n");
        sb.append("        <").append(prefix).append("version>")
                .append(escapeXml(parent.version()))
                .append("</").append(prefix).append("version>\n");
        if (parentPomFile != null) {
            sb.append("        <").append(prefix).append("relativePath>")
                    .append(escapeXml(relativize(childDirectory, parentPomFile)))
                    .append("</").append(prefix).append("relativePath>\n");
        }
        sb.append("    </").append(prefix).append("parent>");
        return sb.toString();
    }

    private static @NotNull String dependencyXml(@NotNull Dependency d, @NotNull String indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent).append("<dependency>\n");
        sb.append(indent).append("    <groupId>").append(escapeXml(d.getGroupId())).append("</groupId>\n");
        sb.append(indent).append("    <artifactId>").append(escapeXml(d.getArtifactId())).append("</artifactId>\n");
        if (!d.getVersion().isEmpty()) {
            sb.append(indent).append("    <version>").append(escapeXml(d.getVersion())).append("</version>\n");
        }
        if (!d.getType().isEmpty()) {
            sb.append(indent).append("    <type>").append(escapeXml(d.getType())).append("</type>\n");
        }
        if (!d.getClassifier().isEmpty()) {
            sb.append(indent).append("    <classifier>").append(escapeXml(d.getClassifier()))
                    .append("</classifier>\n");
        }
        sb.append(indent).append("</dependency>\n");
        return sb.toString();
    }

    /**
     * Character ranges of the blocks that declare dependencies without adding them to the
     * project: {@code <dependencyManagement>} and {@code <profiles>}. Edits must skip these.
     */
    private static @NotNull List<int[]> excludedRegions(@NotNull String content) {
        List<int[]> regions = ignoredMarkupRegions(content);
        String searchable = maskIgnoredMarkup(content);
        // <build> and <reporting> matter as much as the other two: a <plugin> may carry its own
        // <dependencies>, and those blocks look identical to a project dependency in raw text.
        // Without this, editing a dependency that a plugin also declares hits whichever block
        // comes first in the file, updating, or even deleting, the plugin's copy instead.
        for (String tag : new String[]{"dependencyManagement", "profiles", "build", "reporting"}) {
            Matcher m = elementBlockPattern(tag).matcher(searchable);
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

    private static boolean findOutside(@NotNull Matcher matcher, @NotNull List<int[]> regions) {
        while (matcher.find()) {
            if (!inExcludedRegion(regions, matcher.start())) {
                return true;
            }
        }
        return false;
    }

    private static @NotNull List<int[]> ignoredMarkupRegions(@NotNull String content) {
        List<int[]> regions = new ArrayList<>();
        Matcher matcher = IGNORED_MARKUP.matcher(content);
        while (matcher.find()) {
            regions.add(new int[]{matcher.start(), matcher.end()});
        }
        return regions;
    }

    /**
     * Blanks comments and CDATA without moving offsets. Regex-based targeted edits can then
     * locate real XML tags while all replacements are still applied to the original text.
     */
    private static @NotNull String maskIgnoredMarkup(@NotNull String content) {
        char[] masked = content.toCharArray();
        for (int[] region : ignoredMarkupRegions(content)) {
            for (int i = region[0]; i < region[1]; i++) {
                if (masked[i] != '\n' && masked[i] != '\r') {
                    masked[i] = ' ';
                }
            }
        }
        return new String(masked);
    }

    /** Index of the top-level {@code </dependencies>} tag, or -1. */
    private static int findTopLevelDependenciesClose(@NotNull String content) {
        List<int[]> excluded = excludedRegions(content);
        Matcher closes = closeTagPattern("dependencies").matcher(maskIgnoredMarkup(content));
        while (closes.find()) {
            int close = closes.start();
            if (inExcludedRegion(excluded, close)) {
                continue;
            }
            return close;
        }
        return -1;
    }

    private static boolean containsTag(@NotNull String body, @NotNull String tag, @NotNull String value) {
        Pattern p = Pattern.compile(openTagRegex(tag) + "\\s*" + Pattern.quote(escapeXml(value))
                + "\\s*" + closeTagRegex(tag), Pattern.DOTALL);
        return p.matcher(body).find();
    }

    private static boolean matchesDependency(@NotNull String body, @NotNull Dependency dependency) {
        if (!containsTag(body, "groupId", dependency.getGroupId())
                || !containsTag(body, "artifactId", dependency.getArtifactId())) {
            return false;
        }
        String bodyType = firstTagValue(body, "type");
        String wantedType = dependency.getType();
        if (bodyType == null || bodyType.isBlank()) {
            bodyType = "jar";
        }
        if (wantedType.isBlank()) {
            wantedType = "jar";
        }
        String bodyClassifier = firstTagValue(body, "classifier");
        return wantedType.equals(bodyType)
                && dependency.getClassifier().equals(bodyClassifier == null ? "" : bodyClassifier);
    }

    /** Number of references anywhere in the pom; comments make this conservative, never destructive. */
    private static int countPropertyReferences(@NotNull String content, @NotNull String property) {
        Matcher reference = Pattern.compile(Pattern.quote("${" + property + "}")).matcher(content);
        int count = 0;
        while (reference.find()) {
            count++;
        }
        return count;
    }

    private static @Nullable String firstTagValue(@NotNull String body, @NotNull String tag) {
        Matcher m = elementBlockPattern(tag).matcher(body);
        return m.find() ? unescapeXml(m.group(1)) : null;
    }

    private static @Nullable String replacePropertyValue(@NotNull String content,
                                                         @NotNull String property,
                                                         @NotNull String newValue) {
        List<int[]> excluded = excludedRegions(content);
        Matcher m = elementBlockPattern(property).matcher(maskIgnoredMarkup(content));
        while (m.find()) {
            if (inExcludedRegion(excluded, m.start())) {
                continue; // a profile's <properties> does not define the active version
            }
            return replaceElementBody(content, m, newValue);
        }
        return null;
    }

    /** Replaces only an element's text, keeping its prefix, attributes and surrounding whitespace. */
    private static @NotNull String replaceElementBody(@NotNull String content,
                                                       @NotNull Matcher element,
                                                       @NotNull String newValue) {
        String oldBody = content.substring(element.start(1), element.end(1));
        int first = 0;
        while (first < oldBody.length() && Character.isWhitespace(oldBody.charAt(first))) {
            first++;
        }
        int last = oldBody.length();
        while (last > first && Character.isWhitespace(oldBody.charAt(last - 1))) {
            last--;
        }
        String replacement = oldBody.substring(0, first)
                + escapeXml(newValue)
                + oldBody.substring(last);
        return content.substring(0, element.start(1))
                + replacement
                + content.substring(element.end(1));
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

    /**
     * Replaces an existing pom atomically, retaining its POSIX permissions where supported.
     * Every public writer is synchronized as well, so two Maven Assist tasks cannot interleave
     * a read/modify/write sequence.
     */
    private static void writeAtomically(@NotNull Path path, @NotNull String content) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null || !Files.isRegularFile(absolute)) {
            throw new IOException("File no longer exists: " + absolute);
        }
        Set<PosixFilePermission> permissions = null;
        try {
            permissions = Files.getPosixFilePermissions(absolute);
        } catch (UnsupportedOperationException ignored) {
            // Windows and other non-POSIX file systems.
        }
        Path temp = Files.createTempFile(parent, "." + absolute.getFileName(), ".mavenassist.tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            if (permissions != null) {
                Files.setPosixFilePermissions(temp, permissions);
            }
            try {
                Files.move(temp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Creates a new pom without any overwrite window. */
    private static void writeNewAtomically(@NotNull Path path, @NotNull String content) throws IOException {
        Files.writeString(path.toAbsolutePath().normalize(), content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
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
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(true);
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
                    result.put(elementName(el), el.getTextContent().trim());
                }
            }
        }
        return result;
    }

    private static @Nullable Element childElement(@NotNull Element parent, @NotNull String name) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element el && elementName(el).equals(name)) {
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
            if (node instanceof Element el && elementName(el).equals(name)) {
                result.add(el);
            }
        }
        return result;
    }

    private static @Nullable String childText(@NotNull Element parent, @NotNull String name) {
        Element el = childElement(parent, name);
        return el == null ? null : el.getTextContent().trim();
    }

    private static @NotNull String elementName(@NotNull Element element) {
        String localName = element.getLocalName();
        return localName == null ? element.getTagName() : localName;
    }
}

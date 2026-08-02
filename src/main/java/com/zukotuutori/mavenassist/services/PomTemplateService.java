package com.zukotuutori.mavenassist.services;

import com.zukotuutori.mavenassist.maven.PomUtils;
import com.zukotuutori.mavenassist.model.Dependency;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.RoamingType;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Reusable pom templates: everything the pom-creation view lets the user choose
 * (coordinates, parent pom, the newest-version flag and the picked dependencies),
 * stored permanently at application level so they survive IDE restarts.
 */
@Service
@State(name = "MavenAssistPomTemplates",
        storages = @Storage(value = "mavenAssistPomTemplates.xml", roamingType = RoamingType.DISABLED))
public final class PomTemplateService implements PersistentStateComponent<PomTemplateService.State> {

    private static final long MAX_IMPORTED_POM_BYTES = 5L * 1024L * 1024L;

    /** One persisted template. Public fields and a no-arg constructor: required for XML serialization. */
    public static class Template {
        public String name = "";
        public String groupId = "";
        public String artifactId = "";
        public String version = "";
        public String parentPomPath = "";
        public String parentGroupId = "";
        public String parentArtifactId = "";
        public String parentVersion = "";
        public boolean useNewestVersions = true;
        /** Old GAV entries and extended Maven coordinates are both supported. */
        public List<String> dependencies = new ArrayList<>();
    }

    public static class State {
        public List<Template> templates = new ArrayList<>();
    }

    private State state = new State();

    public static PomTemplateService getInstance() {
        return ApplicationManager.getApplication().getService(PomTemplateService.class);
    }

    /**
     * Captures the current create-form state. A blank stored dependency version means
     * "resolve newest"; a non-blank version is the per-row pin chosen by the user.
     */
    public static @NotNull Template snapshot(@NotNull String name,
                                             @NotNull String groupId,
                                             @NotNull String artifactId,
                                             @NotNull String version,
                                             @Nullable String parentPomPath,
                                             @NotNull List<Dependency> dependencies,
                                             @NotNull Map<String, String> customVersions) {
        Template template = new Template();
        template.name = name.trim();
        template.groupId = groupId.trim();
        template.artifactId = artifactId.trim();
        template.version = version.trim();
        setParentReference(template, parentPomPath);
        template.useNewestVersions = customVersions.isEmpty();
        for (Dependency dependency : dependencies) {
            String pinned = customVersions.get(dependency.getKey());
            template.dependencies.add(dependency.withVersion(pinned == null ? "" : pinned)
                    .toTemplateCoordinate());
        }
        return template;
    }

    /** Captures the parts of an existing pom.xml represented by Maven Assist templates. */
    public static @NotNull Template snapshotPom(@NotNull String name, @NotNull File pomFile)
            throws IOException {
        PomUtils.PomInfo info = PomUtils.readPomInfo(pomFile);
        File parentPom = PomUtils.readParentPomFile(pomFile);
        List<Dependency> dependencies = PomUtils.readDependencies(pomFile);
        java.util.HashMap<String, String> versions = new java.util.HashMap<>();
        for (Dependency dependency : dependencies) {
            if (!dependency.getVersion().isEmpty()
                    && !(dependency.getVersion().startsWith("${")
                    && dependency.getVersion().endsWith("}"))) {
                versions.put(dependency.getKey(), dependency.getVersion());
            }
        }
        Template template = snapshot(name, info.groupId(), info.artifactId(), info.version(),
                parentPom == null ? "" : parentPom.getAbsolutePath(), dependencies, versions);
        PomUtils.PomInfo parentInfo = PomUtils.readParentInfo(pomFile);
        if (parentInfo != null) {
            setParentInfo(template, parentInfo);
        }
        return template;
    }

    /** Imports one local pom.xml after enforcing a conservative size limit. */
    public static @NotNull Template importPom(@NotNull String name, @NotNull File pomFile)
            throws IOException {
        if (!pomFile.isFile()) {
            throw new IOException("Selected pom.xml is not a regular file.");
        }
        if (Files.size(pomFile.toPath()) > MAX_IMPORTED_POM_BYTES) {
            throw new IOException("Selected pom.xml is larger than the 5 MB import limit.");
        }
        return snapshotPom(name, pomFile);
    }

    /** Path for local parents, otherwise Maven coordinates for an imported repository parent. */
    public static @NotNull String parentReference(@NotNull Template template) {
        String path = safe(template.parentPomPath);
        if (!path.isEmpty()) {
            return path;
        }
        String groupId = safe(template.parentGroupId);
        String artifactId = safe(template.parentArtifactId);
        String version = safe(template.parentVersion);
        return groupId.isEmpty() || artifactId.isEmpty() || version.isEmpty()
                ? ""
                : groupId + ":" + artifactId + ":" + version;
    }

    /** Applies either a local pom.xml path or a groupId:artifactId:version parent reference. */
    public static void setParentReference(@NotNull Template template, @Nullable String reference) {
        template.parentPomPath = "";
        template.parentGroupId = "";
        template.parentArtifactId = "";
        template.parentVersion = "";
        String value = reference == null ? "" : reference.trim();
        if (value.isEmpty()) {
            return;
        }
        File parentFile = new File(value);
        if (parentFile.isFile()) {
            template.parentPomPath = value;
            try {
                setParentInfo(template, PomUtils.readPomInfo(parentFile));
            } catch (IOException ignored) {
                // Validation at the action boundary reports unreadable parent poms.
            }
            return;
        }
        PomUtils.PomInfo coordinates = parseParentCoordinates(value);
        if (coordinates != null) {
            setParentInfo(template, coordinates);
        } else {
            template.parentPomPath = value;
        }
    }

    public static PomUtils.@Nullable PomInfo parseParentCoordinates(@Nullable String reference) {
        if (reference == null) {
            return null;
        }
        String[] parts = reference.trim().split(":", -1);
        if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
            return null;
        }
        return new PomUtils.PomInfo(parts[0].trim(), parts[1].trim(), parts[2].trim());
    }

    private static void setParentInfo(@NotNull Template template, @NotNull PomUtils.PomInfo info) {
        template.parentGroupId = info.groupId();
        template.parentArtifactId = info.artifactId();
        template.parentVersion = info.version();
    }

    private static @NotNull String safe(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    /** All templates, sorted by name. */
    public @NotNull List<Template> getTemplates() {
        List<Template> result = new ArrayList<>(state.templates);
        result.sort(Comparator.comparing(t -> t.name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    public boolean exists(@NotNull String name) {
        return state.templates.stream().anyMatch(t -> t.name.equalsIgnoreCase(name.trim()));
    }

    /** Stores the template, replacing any existing one with the same name. */
    public void save(@NotNull Template template) {
        state.templates.removeIf(t -> t.name.equalsIgnoreCase(template.name));
        state.templates.add(template);
    }

    public void remove(@NotNull String name) {
        state.templates.removeIf(t -> t.name.equalsIgnoreCase(name));
    }

    @Override
    public @Nullable State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = state;
        if (this.state.templates == null) {
            this.state.templates = new ArrayList<>();
        }
        for (Template template : this.state.templates) {
            if (template.dependencies == null) {
                template.dependencies = new ArrayList<>();
            }
            template.parentPomPath = safe(template.parentPomPath);
            template.parentGroupId = safe(template.parentGroupId);
            template.parentArtifactId = safe(template.parentArtifactId);
            template.parentVersion = safe(template.parentVersion);
        }
    }
}

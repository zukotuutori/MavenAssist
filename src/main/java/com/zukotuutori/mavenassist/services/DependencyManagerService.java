package com.zukotuutori.mavenassist.services;

import com.zukotuutori.mavenassist.model.Dependency;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The "dependency manager": the catalog of dependencies offered in the UI.
 * It consists of a built-in default catalog plus user-added entries (each with a
 * display name) that are persisted permanently (application level, survives IDE restarts).
 */
@Service
@State(name = "MavenAssistDependencyManager", storages = @Storage("mavenAssistDependencies.xml"))
public final class DependencyManagerService implements PersistentStateComponent<DependencyManagerService.State> {

    /** One persisted catalog entry. Public fields for XML serialization. */
    public static class Entry {
        public String name = "";
        public String groupId = "";
        public String artifactId = "";
        public String version = "";
    }

    public static class State {
        /** Keys ("groupId:artifactId") the user deleted from the catalog — hides built-in defaults too. */
        public List<String> removedKeys = new ArrayList<>();
        /** Legacy format ("groupId:artifactId:version" strings) — migrated to {@link #customEntries} on load. */
        public List<String> customDependencies = new ArrayList<>();
        public List<Entry> customEntries = new ArrayList<>();
    }

    private static final List<Dependency> DEFAULTS = List.of(
            new Dependency("org.junit.jupiter", "junit-jupiter", "5.10.2", "JUnit Jupiter (JUnit 5)"),
            new Dependency("junit", "junit", "4.13.2", "JUnit 4"),
            new Dependency("org.mockito", "mockito-core", "5.12.0", "Mockito"),
            new Dependency("org.assertj", "assertj-core", "3.26.0", "AssertJ"),
            new Dependency("com.google.guava", "guava", "33.2.0-jre", "Guava"),
            new Dependency("org.apache.commons", "commons-lang3", "3.14.0", "Apache Commons Lang"),
            new Dependency("commons-io", "commons-io", "2.16.1", "Apache Commons IO"),
            new Dependency("com.fasterxml.jackson.core", "jackson-databind", "2.17.1", "Jackson Databind"),
            new Dependency("com.google.code.gson", "gson", "2.11.0", "Gson"),
            new Dependency("org.slf4j", "slf4j-api", "2.0.13", "SLF4J API"),
            new Dependency("ch.qos.logback", "logback-classic", "1.5.6", "Logback Classic"),
            new Dependency("org.apache.logging.log4j", "log4j-core", "2.23.1", "Log4j Core"),
            new Dependency("org.projectlombok", "lombok", "1.18.32", "Lombok"),
            new Dependency("org.springframework.boot", "spring-boot-starter-web", "3.3.0", "Spring Boot Web"),
            new Dependency("org.springframework.boot", "spring-boot-starter-data-jpa", "3.3.0", "Spring Boot Data JPA"),
            new Dependency("org.springframework.boot", "spring-boot-starter-test", "3.3.0", "Spring Boot Test"),
            new Dependency("org.hibernate.orm", "hibernate-core", "6.5.2.Final", "Hibernate ORM"),
            new Dependency("org.postgresql", "postgresql", "42.7.3", "PostgreSQL Driver"),
            new Dependency("com.mysql", "mysql-connector-j", "8.4.0", "MySQL Connector/J"),
            new Dependency("com.h2database", "h2", "2.2.224", "H2 Database"),
            new Dependency("com.squareup.okhttp3", "okhttp", "4.12.0", "OkHttp"),
            new Dependency("org.apache.httpcomponents.client5", "httpclient5", "5.3.1", "Apache HttpClient 5")
    );

    private State state = new State();

    public static DependencyManagerService getInstance() {
        return ApplicationManager.getApplication().getService(DependencyManagerService.class);
    }

    /**
     * Visible defaults plus all custom entries, sorted by display name. Entries are unique
     * by <em>name</em>; the same groupId:artifactId may appear under different names.
     * A default is hidden when it was deleted or a custom entry took over its name.
     */
    public @NotNull List<Dependency> getAllDependencies() {
        Set<String> customNames = customNames();
        List<Dependency> result = new ArrayList<>();
        for (Dependency d : DEFAULTS) {
            if (!state.removedKeys.contains(d.getKey())
                    && !customNames.contains(d.getName().toLowerCase(Locale.ROOT))) {
                result.add(d);
            }
        }
        for (Entry entry : state.customEntries) {
            if (isValid(entry)) {
                result.add(new Dependency(entry.groupId, entry.artifactId, entry.version, entry.name));
            }
        }
        result.sort(Comparator.comparing(Dependency::getDisplayName, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    private @NotNull Set<String> customNames() {
        Set<String> names = new HashSet<>();
        for (Entry entry : state.customEntries) {
            if (isValid(entry)) {
                names.add(entry.name.trim().toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    private static boolean isValid(@NotNull Entry entry) {
        return entry.groupId != null && !entry.groupId.isBlank()
                && entry.artifactId != null && !entry.artifactId.isBlank();
    }

    /**
     * Un-hides every deleted built-in default; custom entries are untouched, and a default
     * whose name a custom entry took over stays hidden. Returns how many came back.
     */
    public int restoreDefaults() {
        int before = visibleDefaultCount();
        state.removedKeys.clear();
        return visibleDefaultCount() - before;
    }

    private int visibleDefaultCount() {
        Set<String> customNames = customNames();
        int count = 0;
        for (Dependency d : DEFAULTS) {
            if (!state.removedKeys.contains(d.getKey())
                    && !customNames.contains(d.getName().toLowerCase(Locale.ROOT))) {
                count++;
            }
        }
        return count;
    }

    /** True if a visible manager entry already uses this display name (names are unique). */
    public boolean isNameUsed(@NotNull String name) {
        String trimmed = name.trim();
        return getAllDependencies().stream().anyMatch(d -> d.getName().equalsIgnoreCase(trimmed));
    }

    /**
     * Adds a dependency to the manager permanently. Returns false if the name is already
     * used. The coordinates may duplicate an existing entry — only the name must be unique.
     * Deleted entries stay deleted; re-adding the same code creates a fresh entry.
     */
    public boolean addCustomDependency(@NotNull Dependency dependency) {
        if (dependency.getName().isEmpty() || isNameUsed(dependency.getName())) {
            return false;
        }
        Entry entry = new Entry();
        entry.name = dependency.getName();
        entry.groupId = dependency.getGroupId();
        entry.artifactId = dependency.getArtifactId();
        entry.version = dependency.getVersion();
        state.customEntries.add(entry);
        return true;
    }

    /** Deletes exactly this entry: a custom entry is removed, a built-in one is hidden. */
    public void removeDependency(@NotNull Dependency dependency) {
        boolean removedCustom = state.customEntries.removeIf(entry ->
                entry.name != null && entry.name.trim().equalsIgnoreCase(dependency.getName())
                        && (entry.groupId + ":" + entry.artifactId).equals(dependency.getKey()));
        if (!removedCustom && !state.removedKeys.contains(dependency.getKey())) {
            state.removedKeys.add(dependency.getKey());
        }
    }

    @Override
    public @Nullable State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = state;
        if (this.state.customEntries == null) {
            this.state.customEntries = new ArrayList<>();
        }
        if (this.state.removedKeys == null) {
            this.state.removedKeys = new ArrayList<>();
        }
        // Migrate legacy "g:a:v" string entries (from plugin version 1.0.x) to named entries.
        if (this.state.customDependencies != null && !this.state.customDependencies.isEmpty()) {
            for (String gav : this.state.customDependencies) {
                Dependency d = Dependency.fromGav(gav);
                if (d != null) {
                    addCustomDependency(new Dependency(d.getGroupId(), d.getArtifactId(), d.getVersion(),
                            d.getArtifactId()));
                }
            }
            this.state.customDependencies = new ArrayList<>();
        }
    }
}

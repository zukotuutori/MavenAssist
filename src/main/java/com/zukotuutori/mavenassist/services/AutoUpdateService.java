package com.zukotuutori.mavenassist.services;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Remembers which dependencies are marked "auto update", per pom.
 *
 * <p>Project-scoped and keyed by a path <b>relative to the project root</b>, which is what
 * keeps three problems from existing at all rather than needing to be maintained away:
 * flags cannot pile up for projects that are long gone (they are deleted together with the
 * project's workspace file), renaming or moving a project does not silently lose them, and
 * no absolute path, and therefore no user name, is ever written to a roamable or shared file.</p>
 *
 * <p>Stored in workspace.xml rather than a shared {@code .idea/} file on purpose: auto update
 * rewrites poms on its own, and nobody should inherit that merely by cloning a repository.</p>
 *
 * <p>When the flags should be checked lives in {@link AutoUpdateSettings}. That is a
 * preference, not project data.</p>
 */
@Service(Service.Level.PROJECT)
@State(name = "MavenAssistAutoUpdate", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class AutoUpdateService implements PersistentStateComponent<AutoUpdateService.State> {

    public static class State {
        /** Pom path (relative to the project root, '/'-separated) -> "groupId:artifactId" entries. */
        public Map<String, List<String>> flagsByPom = new HashMap<>();
    }

    private final Project project;
    /** The same content as {@link State#flagsByPom}, as sets: this is read on every cell paint. */
    private final Map<String, Set<String>> flags = new HashMap<>();
    private final Set<String> migratedPomKeys = new LinkedHashSet<>();

    public AutoUpdateService(@NotNull Project project) {
        this.project = project;
    }

    public static AutoUpdateService getInstance(@NotNull Project project) {
        return project.getService(AutoUpdateService.class);
    }

    public synchronized boolean isAutoUpdate(@NotNull File pom, @NotNull String dependencyKey) {
        migrateLegacy(pom);
        Set<String> keys = flags.get(pomKey(pom));
        return keys != null && keys.contains(dependencyKey);
    }

    public synchronized void setAutoUpdate(@NotNull File pom,
                                           @NotNull String dependencyKey,
                                           boolean autoUpdate) {
        migrateLegacy(pom);
        String pomKey = pomKey(pom);
        if (autoUpdate) {
            flags.computeIfAbsent(pomKey, k -> new LinkedHashSet<>()).add(dependencyKey);
            return;
        }
        Set<String> keys = flags.get(pomKey);
        if (keys != null && keys.remove(dependencyKey) && keys.isEmpty()) {
            flags.remove(pomKey);
        }
    }

    /**
     * The storage key for a pom: relative to the project root where possible.
     *
     * <p>A pom outside the project has no meaningful relative path, so a one-way hash is
     * stored instead. This keeps the selection stable without putting a home-directory path
     * in workspace.xml.</p>
     */
    private @NotNull String pomKey(@NotNull File pom) {
        return pomKey(project.getBasePath(), pom);
    }

    /** Package-private and static purely so the path handling can be tested without a Project. */
    static @NotNull String pomKey(@Nullable String projectBasePath, @NotNull File pom) {
        Path path = pom.toPath().toAbsolutePath().normalize();
        if (projectBasePath != null && !projectBasePath.isBlank()) {
            Path base = Path.of(projectBasePath).toAbsolutePath().normalize();
            if (path.startsWith(base)) {
                return base.relativize(path).toString().replace(File.separatorChar, '/');
            }
        }
        return "external:" + sha256(path.toString());
    }

    private synchronized void migrateLegacy(@NotNull File pom) {
        String key = pomKey(pom);
        if (!migratedPomKeys.add(key)) {
            return;
        }
        List<String> legacyKeys = LegacyAutoUpdateState.takeForPom(pom);
        if (!legacyKeys.isEmpty()) {
            flags.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).addAll(legacyKeys);
        }
    }

    private static @NotNull String sha256(@NotNull String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : digest) {
                result.append(Character.forDigit((b >>> 4) & 0xf, 16))
                        .append(Character.forDigit(b & 0xf, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    @Override
    public synchronized @Nullable State getState() {
        State state = new State();
        flags.forEach((pom, keys) -> {
            if (!keys.isEmpty()) {
                state.flagsByPom.put(pom, new ArrayList<>(keys));
            }
        });
        return state;
    }

    @Override
    public synchronized void loadState(@NotNull State state) {
        flags.clear();
        migratedPomKeys.clear();
        if (state.flagsByPom == null) {
            return;
        }
        state.flagsByPom.forEach((pom, keys) -> {
            if (keys != null && !keys.isEmpty()) {
                flags.put(pom, new LinkedHashSet<>(keys));
            }
        });
    }
}

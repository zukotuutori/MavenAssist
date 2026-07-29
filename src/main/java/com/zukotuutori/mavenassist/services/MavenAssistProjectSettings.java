package com.zukotuutori.mavenassist.services;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;

/**
 * Remembers the directory the tool window is pointed at, per project.
 *
 * <p>The auto-update triggers that fire without the tool window being open (when the
 * project opens or before a clean install) have no panel to ask, so they read
 * the pom location from here instead, falling back to the project root.</p>
 */
/*
     * Stored in workspace.xml, never in a shared .idea/*.xml. Paths inside the project are
     * persisted relative to its root so the state remains portable and contains no username.
 */
@Service(Service.Level.PROJECT)
@State(name = "MavenAssistProject", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class MavenAssistProjectSettings
        implements PersistentStateComponent<MavenAssistProjectSettings.State> {

    public static class State {
        /** Project-relative path where possible; null means "use the project root". */
        public String directoryPath = null;
    }

    private final Project project;
    private State state = new State();
    /** External selections work for this IDE session but are never serialized. */
    private String externalDirectoryPath;

    public MavenAssistProjectSettings(@NotNull Project project) {
        this.project = project;
    }

    public static MavenAssistProjectSettings getInstance(@NotNull Project project) {
        return project.getService(MavenAssistProjectSettings.class);
    }

    public void setDirectory(@Nullable File directory) {
        if (directory == null) {
            state.directoryPath = null;
            externalDirectoryPath = null;
            return;
        }
        String basePath = project.getBasePath();
        java.nio.file.Path selected = directory.toPath().toAbsolutePath().normalize();
        if (basePath != null && !basePath.isBlank()) {
            java.nio.file.Path base = java.nio.file.Path.of(basePath).toAbsolutePath().normalize();
            if (selected.equals(base)) {
                state.directoryPath = null;
                externalDirectoryPath = null;
                return;
            }
            if (selected.startsWith(base)) {
                state.directoryPath = base.relativize(selected).toString().replace(File.separatorChar, '/');
                externalDirectoryPath = null;
                return;
            }
        }
        // Keep external selections for this panel session only; persisting them would put an
        // absolute home-directory path into workspace.xml.
        state.directoryPath = null;
        externalDirectoryPath = selected.toString();
    }

    /** The chosen directory, or the project root when the user never picked one. */
    public static @Nullable File directory(@NotNull Project project) {
        MavenAssistProjectSettings settings = getInstance(project);
        String stored = settings.externalDirectoryPath != null
                ? settings.externalDirectoryPath : settings.state.directoryPath;
        String basePath = project.getBasePath();
        if ((stored == null || stored.isBlank()) && (basePath == null || basePath.isBlank())) {
            return null;
        }
        File directory;
        if (stored == null || stored.isBlank()) {
            directory = new File(basePath);
        } else {
            File saved = new File(stored);
            directory = saved.isAbsolute() || basePath == null ? saved : new File(basePath, stored);
        }
        if (directory.isDirectory()) {
            return directory;
        }
        File fallback = basePath == null ? null : new File(basePath);
        return fallback != null && fallback.isDirectory() ? fallback : null;
    }

    /** The pom the background triggers operate on, or null when there is none. */
    public static @Nullable File watchedPom(@NotNull Project project) {
        File directory = directory(project);
        if (directory == null) {
            return null;
        }
        File pom = new File(directory, "pom.xml");
        return pom.isFile() ? pom : null;
    }

    @Override
    public @Nullable State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = state;
        if (state.directoryPath != null && new File(state.directoryPath).isAbsolute()) {
            setDirectory(new File(state.directoryPath));
        }
    }
}

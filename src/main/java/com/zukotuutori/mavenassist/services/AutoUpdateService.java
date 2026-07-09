package com.zukotuutori.mavenassist.services;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Remembers which dependencies are marked "auto update" — per pom file, persisted
 * across IDE restarts. Flagged dependencies are bumped to the newest version
 * automatically whenever the project is saved.
 */
@Service
@State(name = "MavenAssistAutoUpdate", storages = @Storage("mavenAssistAutoUpdate.xml"))
public final class AutoUpdateService implements PersistentStateComponent<AutoUpdateService.State> {

    public static class State {
        /** Entries of the form "absolutePomPath|groupId:artifactId". */
        public List<String> entries = new ArrayList<>();
    }

    private State state = new State();

    public static AutoUpdateService getInstance() {
        return ApplicationManager.getApplication().getService(AutoUpdateService.class);
    }

    public boolean isAutoUpdate(@NotNull String pomPath, @NotNull String dependencyKey) {
        return state.entries.contains(entry(pomPath, dependencyKey));
    }

    public void setAutoUpdate(@NotNull String pomPath, @NotNull String dependencyKey, boolean autoUpdate) {
        String entry = entry(pomPath, dependencyKey);
        if (autoUpdate) {
            if (!state.entries.contains(entry)) {
                state.entries.add(entry);
            }
        } else {
            state.entries.remove(entry);
        }
    }

    private static @NotNull String entry(@NotNull String pomPath, @NotNull String dependencyKey) {
        return pomPath + "|" + dependencyKey;
    }

    @Override
    public @Nullable State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = state;
        if (this.state.entries == null) {
            this.state.entries = new ArrayList<>();
        }
    }
}

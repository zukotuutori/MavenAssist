package com.zukotuutori.mavenassist.services;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.RoamingType;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Reads and drains the 1.0.x application-level auto-update entries during migration. */
@Service
@State(name = "MavenAssistAutoUpdate",
        storages = @Storage(value = "mavenAssistAutoUpdate.xml", roamingType = RoamingType.DISABLED))
public final class LegacyAutoUpdateState
        implements PersistentStateComponent<LegacyAutoUpdateState.LegacyState> {

    public static class LegacyState {
        /** Entries in the old {@code absolutePomPath|groupId:artifactId} format. */
        public List<String> entries = new ArrayList<>();
    }

    private LegacyState state = new LegacyState();

    private static @NotNull LegacyAutoUpdateState getInstance() {
        return ApplicationManager.getApplication().getService(LegacyAutoUpdateState.class);
    }

    static @NotNull List<String> takeForPom(@NotNull File pom) {
        LegacyAutoUpdateState service = getInstance();
        synchronized (service) {
            String prefix = pom.toPath().toAbsolutePath().normalize() + "|";
            List<String> keys = new ArrayList<>();
            service.state.entries.removeIf(entry -> {
                if (entry != null && entry.startsWith(prefix)) {
                    String key = entry.substring(prefix.length());
                    if (!key.isBlank()) {
                        keys.add(key);
                    }
                    return true;
                }
                return false;
            });
            return keys;
        }
    }

    @Override
    public synchronized @Nullable LegacyState getState() {
        return state;
    }

    @Override
    public synchronized void loadState(@NotNull LegacyState state) {
        this.state = state;
        if (this.state.entries == null) {
            this.state.entries = new ArrayList<>();
        }
    }
}

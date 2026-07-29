package com.zukotuutori.mavenassist.services;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.RoamingType;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * When the "auto update" flag actually checks Maven Central. A genuine user preference:
 * application-wide and local to this IDE installation. The light-service persistence
 * contract requires roaming to be disabled.
 */
@Service
@State(name = "MavenAssistAutoUpdateTriggers",
        storages = @Storage(value = "mavenAssistAutoUpdate.xml", roamingType = RoamingType.DISABLED))
public final class AutoUpdateSettings implements PersistentStateComponent<AutoUpdateSettings.State> {

    /** The moments at which flagged dependencies are checked against Maven Central. */
    public enum Trigger {
        ON_SAVE("Every time I save the project", true),
        ON_PROJECT_OPEN("When the project is opened", false),
        BEFORE_CLEAN_INSTALL("Right before \"MVN Clean Install\" runs", false),
        ON_SHORTCUT("When I press a shortcut", false);

        public final String displayName;
        public final boolean enabledByDefault;

        Trigger(String displayName, boolean enabledByDefault) {
            this.displayName = displayName;
            this.enabledByDefault = enabledByDefault;
        }
    }

    public static class State {
        /**
         * Whether the user has ever saved the trigger settings. Needed as its own flag
         * because "no triggers at all" is a legitimate choice: an empty {@link #triggers}
         * list is not reliably distinguishable from an unwritten one after a round trip
         * through the XML serializer, and would silently fall back to the defaults.
         */
        public boolean triggersConfigured = false;
        /** Enabled {@link Trigger} names; only consulted when {@link #triggersConfigured}. */
        public List<String> triggers = new ArrayList<>();
    }

    private State state = new State();

    public static AutoUpdateSettings getInstance() {
        return ApplicationManager.getApplication().getService(AutoUpdateSettings.class);
    }

    public synchronized boolean isTriggerEnabled(@NotNull Trigger trigger) {
        return state.triggersConfigured
                ? state.triggers.contains(trigger.name())
                : trigger.enabledByDefault;
    }

    public synchronized void setTriggerEnabled(@NotNull Trigger trigger, boolean enabled) {
        if (!state.triggersConfigured) {
            // Materialize the defaults first, so setting one trigger keeps the others as they were.
            state.triggers.clear();
            for (Trigger known : Trigger.values()) {
                if (known.enabledByDefault) {
                    state.triggers.add(known.name());
                }
            }
            state.triggersConfigured = true;
        }
        if (enabled) {
            if (!state.triggers.contains(trigger.name())) {
                state.triggers.add(trigger.name());
            }
        } else {
            state.triggers.remove(trigger.name());
        }
    }

    @Override
    public synchronized @Nullable State getState() {
        return state;
    }

    @Override
    public synchronized void loadState(@NotNull State state) {
        this.state = state;
        if (this.state.triggers == null) {
            this.state.triggers = new ArrayList<>();
        }
    }
}

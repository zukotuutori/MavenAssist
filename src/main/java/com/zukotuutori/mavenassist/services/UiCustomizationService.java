package com.zukotuutori.mavenassist.services;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists which dependency fields are shown directly in the table and which only
 * in the "Details" dialog. Saved at application level, so the choice survives IDE restarts.
 */
@Service
@State(name = "MavenAssistUiCustomization", storages = @Storage("mavenAssistUiCustomization.xml"))
public final class UiCustomizationService implements PersistentStateComponent<UiCustomizationService.State> {

    /** Dependency fields whose visibility (table vs. details dialog) can be toggled. */
    public enum Field {
        NAME("Name", true, true),
        GROUP_ID("Group id", false, true),
        ARTIFACT_ID("Artifact id", false, true),
        VERSION("Version", true, true),
        LATEST_VERSION("Newest version", false, true),
        AUTO_UPDATE("Auto update", true, true);

        public final String displayName;
        public final boolean defaultInTable;
        public final boolean defaultInDetails;

        Field(String displayName, boolean defaultInTable, boolean defaultInDetails) {
            this.displayName = displayName;
            this.defaultInTable = defaultInTable;
            this.defaultInDetails = defaultInDetails;
        }
    }

    public static class State {
        public Map<String, Boolean> fieldInTable = new HashMap<>();
        public Map<String, Boolean> fieldInDetails = new HashMap<>();
    }

    private State state = new State();
    private final List<Runnable> changeListeners = new ArrayList<>();

    public static UiCustomizationService getInstance() {
        return ApplicationManager.getApplication().getService(UiCustomizationService.class);
    }

    // ---------------------------------------------------------------- live updates

    public void addChangeListener(@NotNull Runnable listener) {
        changeListeners.add(listener);
    }

    public void removeChangeListener(@NotNull Runnable listener) {
        changeListeners.remove(listener);
    }

    /** Tells every open UI to re-read the field visibility. Call on the EDT. */
    public void notifyCustomizationChanged() {
        for (Runnable listener : new ArrayList<>(changeListeners)) {
            listener.run();
        }
    }

    // ---------------------------------------------------------------- field visibility

    public boolean isShownInTable(@NotNull Field field) {
        return state.fieldInTable.getOrDefault(field.name(), field.defaultInTable);
    }

    public boolean isShownInDetails(@NotNull Field field) {
        return state.fieldInDetails.getOrDefault(field.name(), field.defaultInDetails);
    }

    public void setShownInTable(@NotNull Field field, boolean shown) {
        state.fieldInTable.put(field.name(), shown);
    }

    public void setShownInDetails(@NotNull Field field, boolean shown) {
        state.fieldInDetails.put(field.name(), shown);
    }

    /** The table's visible dependency fields, in their canonical column order. */
    public @NotNull List<Field> visibleTableFields() {
        List<Field> visible = new ArrayList<>();
        for (Field field : Field.values()) {
            if (isShownInTable(field)) {
                visible.add(field);
            }
        }
        return visible;
    }

    public void resetToDefaults() {
        state.fieldInTable.clear();
        state.fieldInDetails.clear();
    }

    @Override
    public @Nullable State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = state;
        if (this.state.fieldInTable == null) {
            this.state.fieldInTable = new HashMap<>();
        }
        if (this.state.fieldInDetails == null) {
            this.state.fieldInDetails = new HashMap<>();
        }
    }
}

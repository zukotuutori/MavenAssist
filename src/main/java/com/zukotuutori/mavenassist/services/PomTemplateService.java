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
import java.util.Comparator;
import java.util.List;

/**
 * Reusable pom templates: everything the pom-creation view lets the user choose
 * (coordinates, parent pom, the newest-version flag and the picked dependencies),
 * stored permanently at application level so they survive IDE restarts.
 */
@Service
@State(name = "MavenAssistPomTemplates",
        storages = @Storage(value = "mavenAssistPomTemplates.xml", roamingType = RoamingType.DISABLED))
public final class PomTemplateService implements PersistentStateComponent<PomTemplateService.State> {

    /** One persisted template. Public fields and a no-arg constructor: required for XML serialization. */
    public static class Template {
        public String name = "";
        public String groupId = "";
        public String artifactId = "";
        public String version = "";
        public String parentPomPath = "";
        public boolean useNewestVersions = true;
        /** "groupId:artifactId:version" per entry; the version may be empty. */
        public List<String> dependencies = new ArrayList<>();
    }

    public static class State {
        public List<Template> templates = new ArrayList<>();
    }

    private State state = new State();

    public static PomTemplateService getInstance() {
        return ApplicationManager.getApplication().getService(PomTemplateService.class);
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
        }
    }
}

package com.zukotuutori.mavenassist.ui;

import com.zukotuutori.mavenassist.model.Dependency;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

/** Shared confirmation for every UI control that toggles auto update. */
final class AutoUpdateConfirmation {

    private AutoUpdateConfirmation() {
    }

    static boolean confirm(@NotNull Project project,
                           @NotNull Dependency dependency,
                           boolean enable) {
        String name = dependency.getName().isEmpty()
                ? dependency.getKey()
                : dependency.getName() + " (" + dependency.getKey() + ")";
        String action = enable ? "enable" : "disable";
        return Messages.showYesNoDialog(project,
                "Are you sure you want to " + action + " auto update for\n" + name + "?",
                enable ? "Enable Auto Update" : "Disable Auto Update",
                "Yes", "No", Messages.getQuestionIcon()) == Messages.YES;
    }
}

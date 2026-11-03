package com.zukotuutori.mavenassist.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Asks where a version that a parent pom's property controls should be written: into the
 * property, which moves every module inheriting it, or into this pom as a literal version.
 */
final class ParentPropertyChoice {

    enum Choice {
        /** Bump the property in the pom that declares it. */
        PARENT,
        /** Leave the parent alone and write the version into this pom's dependency. */
        THIS_POM,
        /** Change nothing. */
        CANCEL
    }

    private ParentPropertyChoice() {
    }

    /** Shows the question on the EDT and blocks the calling background thread until it is answered. */
    static @NotNull Choice ask(@NotNull Project project, @NotNull String message) {
        AtomicInteger answer = new AtomicInteger(Messages.CANCEL);
        ApplicationManager.getApplication().invokeAndWait(() -> answer.set(
                Messages.showYesNoCancelDialog(project, message, "Version Comes From a Parent pom.xml",
                        "Update Parent Property", "Only This pom.xml", "Cancel",
                        Messages.getQuestionIcon())));
        return switch (answer.get()) {
            case Messages.YES -> Choice.PARENT;
            case Messages.NO -> Choice.THIS_POM;
            default -> Choice.CANCEL;
        };
    }
}

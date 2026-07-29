package com.zukotuutori.mavenassist.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Component;

/**
 * Asks for a custom version. Custom repository versions are valid Maven input, so this
 * deliberately performs no Maven Central lookup and never rejects a private artifact.
 */
final class VersionInput {

    private VersionInput() {
    }

    /**
     * @return the trimmed version, "" when the field was left empty, or null on cancel.
     */
    static @Nullable String ask(@Nullable Project project, @Nullable Component parent,
                                @NotNull String groupId, @NotNull String artifactId,
                                @NotNull String message, @Nullable String initial) {
        String input = Messages.showInputDialog(parent, message, "Change Version", null, initial, null);
        return input == null ? null : input.trim();
    }
}

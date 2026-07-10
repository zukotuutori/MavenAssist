package com.zukotuutori.mavenassist.ui;

import com.zukotuutori.mavenassist.maven.MavenCentralClient;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.InputValidatorEx;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Component;

/**
 * Asks for a custom version and verifies on OK that it exists on Maven Central.
 * A version Central does not know re-opens the field with a red
 * "This version does not exist" error underneath. When the check cannot run
 * (offline, cancelled), the input is accepted rather than blocking the user.
 */
final class VersionInput {

    static final String NOT_FOUND = "This version does not exist";

    private VersionInput() {
    }

    /**
     * @return the trimmed version, "" when the field was left empty, or null on cancel.
     */
    static @Nullable String ask(@Nullable Project project, @Nullable Component parent,
                                @NotNull String groupId, @NotNull String artifactId,
                                @NotNull String message, @Nullable String initial) {
        String value = initial;
        String rejected = null;
        while (true) {
            String bad = rejected;
            String input = Messages.showInputDialog(parent, message, "Change Version", null, value,
                    new InputValidatorEx() {
                        @Override
                        public @Nullable String getErrorText(String inputString) {
                            return inputString != null && inputString.trim().equals(bad) ? NOT_FOUND : null;
                        }
                    });
            if (input == null) {
                return null; // cancelled
            }
            String trimmed = input.trim();
            if (trimmed.isEmpty()) {
                return "";
            }
            if (!Boolean.FALSE.equals(existsOnCentral(project, groupId, artifactId, trimmed))) {
                return trimmed;
            }
            // Re-open with the rejected value so the red error shows right under the field.
            rejected = trimmed;
            value = trimmed;
        }
    }

    /** null = could not verify (offline, cancelled) — never treated as "does not exist". */
    private static @Nullable Boolean existsOnCentral(@Nullable Project project,
                                                     @NotNull String groupId,
                                                     @NotNull String artifactId,
                                                     @NotNull String version) {
        try {
            return ProgressManager.getInstance().runProcessWithProgressSynchronously(
                    () -> MavenCentralClient.versionExists(groupId, artifactId, version),
                    "Checking version on Maven Central...", true, project);
        } catch (ProcessCanceledException e) {
            return null;
        }
    }
}

package com.zukotuutori.mavenassist.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import com.zukotuutori.mavenassist.maven.MavenCentralClient;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import java.awt.Component;
import java.awt.Dimension;
import java.util.List;

/**
 * Asks for a custom version and reports, while typing, whether Maven Central publishes it.
 * Confirming a version that Maven Central demonstrably does not have raises an error and keeps
 * the dialog open; a version that could not be checked at all is accepted, so artifacts from
 * private repositories stay usable.
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
        VersionInputDialog dialog =
                new VersionInputDialog(project, parent, groupId, artifactId, message, initial);
        return dialog.showAndGet() ? dialog.version() : null;
    }

    /** Text field plus a live "does this version exist on Maven Central?" status line. */
    private static class VersionInputDialog extends DialogWrapper {

        /** Waits for a pause in typing so that one lookup covers the whole version. */
        private static final int TYPING_PAUSE_MS = 350;

        private final @Nullable Project project;
        private final String groupId;
        private final String artifactId;
        private final String message;
        private final JBTextField versionField = new JBTextField();
        private final JBLabel statusLabel = new JBLabel(" ");
        private final Timer checkTimer;

        /** Guards against a slow lookup overwriting the status of a newer one. */
        private String pendingVersion = "";
        /** The version the last completed lookup was judged against, so OK need not refetch. */
        private @Nullable String checkedVersion;
        private @Nullable List<String> checkedVersions;

        VersionInputDialog(@Nullable Project project, @Nullable Component parent,
                           @NotNull String groupId, @NotNull String artifactId,
                           @NotNull String message, @Nullable String initial) {
            super(project, parent, false, IdeModalityType.IDE);
            this.project = project;
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.message = message;
            versionField.setText(initial == null ? "" : initial);
            checkTimer = new Timer(TYPING_PAUSE_MS, e -> checkVersion());
            checkTimer.setRepeats(false);
            setTitle("Change Version");
            init();
            checkVersion();
        }

        @NotNull String version() {
            return versionField.getText().trim();
        }

        @Override
        public @Nullable JComponent getPreferredFocusedComponent() {
            return versionField;
        }

        @Override
        protected @NotNull JComponent createCenterPanel() {
            JPanel panel = new JPanel();
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            for (String line : message.split("\n")) {
                JBLabel label = new JBLabel(UiText.html(line));
                label.setAlignmentX(Component.LEFT_ALIGNMENT);
                panel.add(label);
            }
            panel.add(javax.swing.Box.createVerticalStrut(JBUI.scale(6)));

            versionField.setAlignmentX(Component.LEFT_ALIGNMENT);
            versionField.setPreferredSize(new Dimension(JBUI.scale(320),
                    versionField.getPreferredSize().height));
            versionField.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                    versionField.getPreferredSize().height));
            versionField.getDocument().addDocumentListener(new DocumentAdapter() {
                @Override
                protected void textChanged(@NotNull DocumentEvent e) {
                    statusLabel.setForeground(JBColor.GRAY);
                    statusLabel.setText("Checking Maven Central...");
                    checkTimer.restart();
                }
            });
            panel.add(versionField);
            panel.add(javax.swing.Box.createVerticalStrut(JBUI.scale(4)));

            statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            statusLabel.setForeground(JBColor.GRAY);
            panel.add(statusLabel);
            return panel;
        }

        /**
         * A version the artifact does not publish is refused here, so that a typo never reaches
         * the pom. Only a verified absence blocks: when Maven Central cannot be reached the
         * version is accepted, which also keeps versions from private repositories usable.
         */
        @Override
        protected void doOKAction() {
            String version = version();
            if (version.isEmpty()) {
                super.doOKAction();
                return;
            }
            List<String> available = lookupWithProgress(version);
            if (available == null || available.isEmpty() || available.contains(version)) {
                super.doOKAction();
                return;
            }
            showVerdict(version, available);
            Messages.showErrorDialog(getContentPanel(),
                    "Version " + version + " does not exist for " + groupId + ":" + artifactId
                            + " on Maven Central.\n\nNewest available version: "
                            + available.get(available.size() - 1),
                    "Unknown Version");
            versionField.requestFocusInWindow();
            versionField.selectAll();
        }

        /** The version list for OK: cached from the typing check, otherwise fetched right now. */
        private @Nullable List<String> lookupWithProgress(@NotNull String version) {
            if (version.equals(checkedVersion)) {
                return checkedVersions;
            }
            checkTimer.stop();
            String group = groupId;
            String artifact = artifactId;
            List<String> available = ProgressManager.getInstance().runProcessWithProgressSynchronously(
                    () -> MavenCentralClient.getAvailableVersions(group, artifact),
                    "Checking Version on Maven Central", true, project);
            rememberVerdict(version, available);
            return available;
        }

        /** Looks the typed version up off the EDT and shows the verdict when it comes back. */
        private void checkVersion() {
            String version = version();
            pendingVersion = version;
            if (version.isEmpty()) {
                setStatus(JBColor.GRAY, "The newest version from Maven Central is used.");
                return;
            }
            statusLabel.setForeground(JBColor.GRAY);
            statusLabel.setText("Checking Maven Central...");
            String group = groupId;
            String artifact = artifactId;
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                List<String> available = MavenCentralClient.getAvailableVersions(group, artifact);
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!version.equals(pendingVersion)) {
                        return; // the field moved on while this lookup was in flight
                    }
                    rememberVerdict(version, available);
                    showVerdict(version, available);
                }, ModalityState.any());
            });
        }

        private void rememberVerdict(@NotNull String version, @Nullable List<String> available) {
            checkedVersion = version;
            checkedVersions = available;
        }

        private void showVerdict(@NotNull String version, @Nullable List<String> available) {
            if (available == null || available.isEmpty()) {
                setStatus(JBColor.GRAY, "Maven Central not reachable or artifact unknown - "
                        + "the version cannot be verified.");
                return;
            }
            if (available.contains(version)) {
                setStatus(new JBColor(0x2E7D32, 0x6DB33F),
                        "Version " + version + " exists on Maven Central.");
                return;
            }
            setStatus(new JBColor(0xB00020, 0xE06C75),
                    "Version " + version + " does not exist on Maven Central (newest there: "
                            + available.get(available.size() - 1) + ").");
        }

        private void setStatus(@NotNull JBColor color, @NotNull String text) {
            statusLabel.setForeground(color);
            // Wrap inside the dialog instead of widening it: verdicts can be a full sentence.
            statusLabel.setText("<html><body style='width:" + JBUI.scale(320) + "px'>"
                    + com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(text) + "</body></html>");
        }

        @Override
        protected void dispose() {
            checkTimer.stop();
            super.dispose();
        }
    }
}

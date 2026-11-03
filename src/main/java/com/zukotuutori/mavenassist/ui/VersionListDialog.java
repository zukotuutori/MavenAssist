package com.zukotuutori.mavenassist.ui;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import com.zukotuutori.mavenassist.maven.MavenCentralClient;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lists every version Maven Central publishes for an artifact and lets the user pick one.
 * Pre-releases and snapshots stay in the list: picking from it is a deliberate choice, unlike
 * the unattended "newest version" path that deliberately skips them.
 */
final class VersionListDialog extends DialogWrapper {

    private final String groupId;
    private final String artifactId;
    private final @Nullable String currentVersion;
    /** Every published version, newest first. */
    private final List<String> versions;
    private final DefaultListModel<String> model = new DefaultListModel<>();
    private final JBList<String> list = new JBList<>(model);
    private final JBTextField filterField = new JBTextField();

    /**
     * Fetches the version list and asks the user to pick one.
     *
     * @return the chosen version, or null when nothing could be fetched or the dialog was cancelled.
     */
    static @Nullable String choose(@Nullable Project project, @NotNull Component parent,
                                   @NotNull String groupId, @NotNull String artifactId,
                                   @Nullable String currentVersion) {
        List<String> available = ProgressManager.getInstance().runProcessWithProgressSynchronously(
                () -> MavenCentralClient.getAvailableVersions(groupId, artifactId),
                "Loading Versions from Maven Central", true, project);
        if (available == null || available.isEmpty()) {
            Messages.showErrorDialog(parent,
                    "No versions found for " + groupId + ":" + artifactId + " on Maven Central.\n\n"
                            + "The artifact may be unknown there, or Maven Central could not be reached.",
                    "No Versions Available");
            return null;
        }
        VersionListDialog dialog =
                new VersionListDialog(project, parent, groupId, artifactId, currentVersion, available);
        return dialog.showAndGet() ? dialog.list.getSelectedValue() : null;
    }

    private VersionListDialog(@Nullable Project project, @NotNull Component parent,
                              @NotNull String groupId, @NotNull String artifactId,
                              @Nullable String currentVersion, @NotNull List<String> oldestFirst) {
        super(project, parent, false, IdeModalityType.IDE);
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.currentVersion = currentVersion == null || currentVersion.isBlank() ? null : currentVersion;
        this.versions = new ArrayList<>(oldestFirst);
        java.util.Collections.reverse(this.versions);
        setTitle("Available Versions");
        setOKButtonText("Use Version");
        init();
        refill();
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return filterField;
    }

    @Override
    protected @NotNull JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        panel.setPreferredSize(new Dimension(JBUI.scale(420), JBUI.scale(400)));

        panel.add(new JBLabel(UiText.html(versions.size() + " versions of " + groupId + ":" + artifactId
                + " on Maven Central:")), BorderLayout.NORTH);

        JPanel body = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        filterField.getEmptyText().setText("Filter versions");
        filterField.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                refill();
            }
        });
        body.add(filterField, BorderLayout.NORTH);

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> source, Object value, int index,
                                                          boolean selected, boolean focused) {
                super.getListCellRendererComponent(source, value, index, selected, focused);
                String version = String.valueOf(value);
                setText(UiText.html(version.equals(currentVersion) ? version + "  (current)" : version));
                return this;
            }
        });
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && list.getSelectedValue() != null) {
                    doOKAction();
                }
            }
        });
        body.add(new JBScrollPane(list), BorderLayout.CENTER);
        panel.add(body, BorderLayout.CENTER);
        return panel;
    }

    /** Shows the versions matching the filter, preselecting the current one when it is visible. */
    private void refill() {
        String filter = filterField.getText().trim().toLowerCase(Locale.ROOT);
        model.clear();
        for (String version : versions) {
            if (filter.isEmpty() || version.toLowerCase(Locale.ROOT).contains(filter)) {
                model.addElement(version);
            }
        }
        if (model.isEmpty()) {
            return;
        }
        int index = currentVersion == null ? -1 : model.indexOf(currentVersion);
        list.setSelectedIndex(index < 0 ? 0 : index);
        list.ensureIndexIsVisible(list.getSelectedIndex());
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        if (model.isEmpty()) {
            return new ValidationInfo("No version matches the filter.", filterField);
        }
        if (list.getSelectedValue() == null) {
            return new ValidationInfo("Select a version.", list);
        }
        return null;
    }
}

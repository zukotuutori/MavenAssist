package com.zukotuutori.mavenassist.ui;

import com.zukotuutori.mavenassist.maven.MavenCentralClient;
import com.zukotuutori.mavenassist.maven.PomUtils;
import com.zukotuutori.mavenassist.model.Dependency;
import com.zukotuutori.mavenassist.services.DependencyManagerService;
import com.zukotuutori.mavenassist.services.PomTemplateService;
import com.zukotuutori.mavenassist.util.Notifier;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shown when the chosen directory contains no pom.xml: lets the user create one,
 * optionally with a parent pom (chosen via path) and dependencies picked by clicking.
 */
public class CreatePomPanel extends JPanel {

    private final Project project;
    private final Runnable onPomCreated;

    private final JTextField groupIdField = new JTextField("com.example");
    private final JTextField artifactIdField = new JTextField("my-app");
    private final JTextField versionField = new JTextField("1.0.0");
    private final JTextField parentPomField = new JTextField();
    private final SearchTextField dependencySearchField = new SearchTextField();
    private final DependencyPickerTable dependencyPicker =
            new DependencyPickerTable(this::showDependencyDetails, true);

    private File targetDirectory;

    public CreatePomPanel(@NotNull Project project, @NotNull Runnable onPomCreated) {
        super(new BorderLayout(0, JBUI.scale(6)));
        this.project = project;
        this.onPomCreated = onPomCreated;
        setBorder(JBUI.Borders.empty(8));

        add(new SectionBox("New pom.xml", com.intellij.icons.AllIcons.FileTypes.Xml, buildFormPanel()),
                BorderLayout.NORTH);
        add(new SectionBox("Dependencies", com.intellij.icons.AllIcons.Nodes.PpLib, buildDependencyPanel()),
                BorderLayout.CENTER);
        add(new SectionBox("Actions", com.intellij.icons.AllIcons.Actions.Execute, buildButtonPanel()),
                BorderLayout.SOUTH);

        reloadDependencyList();
    }

    public void setTargetDirectory(@NotNull File directory) {
        this.targetDirectory = directory;
        reloadDependencyList();
    }

    // ---------------------------------------------------------------- UI

    private @NotNull JPanel buildFormPanel() {
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = JBUI.insets(2, 2);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        form.setOpaque(false);
        int row = 0;
        addFormRow(form, gbc, row++, "No pom.xml found in this directory: Create one:", null);
        addFormRow(form, gbc, row++, "Group id:", groupIdField);
        addFormRow(form, gbc, row++, "Artifact id:", artifactIdField);
        addFormRow(form, gbc, row++, "Version:", versionField);

        gbc.gridy = row;
        gbc.gridx = 0;
        gbc.weightx = 0;
        form.add(new JBLabel("Parent pom (optional):"), gbc);

        JPanel parentPanel = new JPanel(new BorderLayout(JBUI.scale(4), 0));
        parentPanel.setOpaque(false);
        parentPanel.add(parentPomField, BorderLayout.CENTER);
        JButton browseParent = new JButton("Choose Path...");
        browseParent.addActionListener(e -> chooseParentPom());
        parentPanel.add(browseParent, BorderLayout.EAST);

        gbc.gridx = 1;
        gbc.weightx = 1;
        form.add(parentPanel, gbc);

        return form;
    }

    private void addFormRow(@NotNull JPanel form, @NotNull GridBagConstraints gbc, int row,
                            @NotNull String label, @Nullable JTextField field) {
        gbc.gridy = row;
        gbc.gridx = 0;
        gbc.weightx = 0;
        gbc.gridwidth = field == null ? 2 : 1;
        form.add(new JBLabel(label), gbc);
        gbc.gridwidth = 1;
        if (field != null) {
            gbc.gridx = 1;
            gbc.weightx = 1;
            form.add(field, gbc);
        }
    }

    private @NotNull JPanel buildDependencyPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        panel.setOpaque(false);
        JPanel header = new JPanel(new BorderLayout(0, JBUI.scale(2)));
        header.setOpaque(false);
        header.add(new JBLabel("Tick to select, search to filter:"), BorderLayout.NORTH);

        JPanel searchRow = new JPanel(new BorderLayout(JBUI.scale(4), 0));
        searchRow.setOpaque(false);
        searchRow.add(dependencySearchField, BorderLayout.CENTER);

        JPanel searchButtons = new JPanel(new java.awt.GridLayout(1, 2, JBUI.scale(4), 0));
        searchButtons.setOpaque(false);
        JButton selectAllButton = new JButton("Select All");
        selectAllButton.setToolTipText("Tick every dependency currently shown (search applied)");
        selectAllButton.addActionListener(e -> dependencyPicker.checkAllVisible());
        searchButtons.add(selectAllButton);
        JButton chooseTemplateButton = new JButton("Choose Template");
        chooseTemplateButton.setToolTipText("Fill the whole form from a saved template");
        chooseTemplateButton.addActionListener(e -> chooseTemplate());
        searchButtons.add(chooseTemplateButton);
        searchRow.add(searchButtons, BorderLayout.EAST);

        header.add(searchRow, BorderLayout.SOUTH);

        panel.add(header, BorderLayout.NORTH);
        panel.add(new JBScrollPane(dependencyPicker), BorderLayout.CENTER);
        dependencyPicker.bindSearch(dependencySearchField);
        return panel;
    }

    private @NotNull JPanel buildButtonPanel() {
        // Vertical stack of full-width buttons: stays fully visible even in a narrow tool window
        // (a FlowLayout would wrap buttons into a clipped, invisible second row).
        JPanel panel = new JPanel(new java.awt.GridLayout(0, 1, 0, JBUI.scale(4)));
        panel.setBorder(JBUI.Borders.emptyTop(4));

        JButton addToManagerButton = new JButton("Add Dependencies to Manager");
        addToManagerButton.addActionListener(e -> {
            // Reload regardless of OK/Cancel: the dialog's restore button changes the catalog too.
            new AddToManagerDialog(project).showAndGet();
            reloadDependencyList();
        });
        panel.add(addToManagerButton);

        JButton createButton = new JButton("Create pom.xml");
        createButton.addActionListener(e -> createPom());
        panel.add(createButton);

        return panel;
    }

    private void reloadDependencyList() {
        // Checked entries stay checked across reloads — the picker matches them by key.
        dependencyPicker.setDependencies(DependencyManagerService.getInstance().getAllDependencies());
    }

    private @NotNull List<Dependency> getSelectedDependencies() {
        return dependencyPicker.getCheckedDependencies();
    }

    private void showDependencyDetails(@NotNull Dependency dependency) {
        new PickableDetailsDialog(dependency).show();
    }

    private void chooseTemplate() {
        ChooseTemplateDialog dialog = new ChooseTemplateDialog(project);
        if (dialog.showAndGet()) {
            PomTemplateService.Template template = dialog.getSelectedTemplate();
            if (template != null) {
                applyTemplate(template);
            }
        }
    }

    /**
     * Fills the whole form from the template. Dependencies are matched by
     * groupId:artifactId against the manager's catalog; entries that were removed
     * from the catalog in the meantime cannot be ticked and are reported.
     */
    private void applyTemplate(@NotNull PomTemplateService.Template template) {
        groupIdField.setText(template.groupId);
        artifactIdField.setText(template.artifactId);
        versionField.setText(template.version);
        parentPomField.setText(template.parentPomPath == null ? "" : template.parentPomPath);

        reloadDependencyList();
        dependencyPicker.checkNone();

        Set<String> available = new HashSet<>();
        for (Dependency d : DependencyManagerService.getInstance().getAllDependencies()) {
            available.add(d.getKey());
        }
        List<String> missing = new ArrayList<>();
        for (String gav : template.dependencies) {
            Dependency d = Dependency.fromGav(gav);
            if (d == null) {
                continue;
            }
            if (available.contains(d.getKey())) {
                dependencyPicker.setChecked(d.getKey(), true);
            } else {
                missing.add(d.getKey());
            }
        }
        if (!missing.isEmpty()) {
            Notifier.warn(project, "Template \"" + template.name + "\" references dependencies that are no longer "
                    + "in the manager and were skipped: " + String.join(", ", missing));
        }
    }

    private void chooseParentPom() {
        FileChooserDescriptor descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("xml")
                .withTitle("Choose Parent pom.xml");
        VirtualFile chosen = FileChooser.chooseFile(descriptor, project, null);
        if (chosen != null) {
            parentPomField.setText(chosen.getPath());
        }
    }

    /**
     * Maven refuses parent poms whose packaging is not "pom". Offers to fix the chosen
     * parent; returns false when the user cancels instead.
     */
    private boolean ensureParentPackaging(@NotNull File parentFile) {
        try {
            String packaging = PomUtils.readPackaging(parentFile);
            if ("pom".equals(packaging)) {
                return true;
            }
            int answer = com.intellij.openapi.ui.Messages.showYesNoDialog(project,
                    "Maven requires a parent pom to have <packaging>pom</packaging>, but\n"
                            + parentFile.getAbsolutePath() + "\nhas \"" + packaging + "\".\n\n"
                            + "Change its packaging to \"pom\"? (Its own build output changes accordingly.)",
                    "Fix Parent Packaging",
                    "Change to \"pom\"", "Cancel",
                    com.intellij.openapi.ui.Messages.getWarningIcon());
            if (answer != com.intellij.openapi.ui.Messages.YES) {
                Notifier.warn(project, "pom.xml not created — the parent's packaging must be \"pom\".");
                return false;
            }
            PomUtils.ensurePomPackaging(parentFile);
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(parentFile);
            return true;
        } catch (java.io.IOException e) {
            Notifier.error(project, "Could not read parent pom: " + e.getMessage());
            return false;
        }
    }

    // ---------------------------------------------------------------- action

    private void createPom() {
        File directory = targetDirectory;
        if (directory == null || !directory.isDirectory()) {
            Notifier.warn(project, "No valid target directory chosen.");
            return;
        }
        String groupId = groupIdField.getText().trim();
        String artifactId = artifactIdField.getText().trim();
        String version = versionField.getText().trim();
        if (groupId.isEmpty() || artifactId.isEmpty() || version.isEmpty()) {
            Notifier.warn(project, "Group id, artifact id and version must not be empty.");
            return;
        }

        String parentPath = parentPomField.getText().trim();
        File parentPomFile = parentPath.isEmpty() ? null : new File(parentPath);
        if (parentPomFile != null && !parentPomFile.isFile()) {
            Notifier.warn(project, "Parent pom not found: " + parentPath);
            return;
        }
        if (parentPomFile != null && !ensureParentPackaging(parentPomFile)) {
            return;
        }

        List<Dependency> selected = getSelectedDependencies();
        Map<String, String> customVersions = dependencyPicker.getCustomVersions();
        File pomFile = new File(directory, "pom.xml");

        new Task.Backgroundable(project, "Maven Assist: Creating pom.xml", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    PomUtils.PomInfo parentInfo = null;
                    if (parentPomFile != null) {
                        indicator.setText("Reading parent pom...");
                        parentInfo = PomUtils.readPomInfo(parentPomFile);
                    }

                    List<Dependency> resolved = new ArrayList<>();
                    for (Dependency dep : selected) {
                        indicator.checkCanceled();
                        // A pinned version always wins; otherwise the newest release is used
                        // (falling back to the catalog version if Central is unreachable).
                        String depVersion = customVersions.get(dep.getKey());
                        if (depVersion == null) {
                            indicator.setText("Fetching newest version of " + dep.getKey() + "...");
                            String latest = MavenCentralClient.getLatestVersion(dep.getGroupId(), dep.getArtifactId());
                            depVersion = latest != null ? latest : dep.getVersion();
                        }
                        resolved.add(dep.withVersion(depVersion));
                    }

                    indicator.setText("Writing pom.xml...");
                    PomUtils.createPom(pomFile, groupId, artifactId, version, parentInfo, parentPomFile, resolved);

                    ApplicationManager.getApplication().invokeLater(() -> {
                        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(pomFile);
                        Notifier.info(project, "Created " + pomFile.getAbsolutePath()
                                + (resolved.isEmpty() ? "" : " with " + resolved.size() + " dependencies."));
                        onPomCreated.run();
                    });
                } catch (Exception ex) {
                    Notifier.error(project, "Failed to create pom.xml: " + ex.getMessage());
                }
            }
        }.queue();
    }

    // ---------------------------------------------------------------- details dialog

    /**
     * Info-only details of one pickable dependency: coordinates, catalog version and the
     * newest version from Maven Central (version pinning happens later, in the dependency
     * table of the created pom).
     */
    private class PickableDetailsDialog extends DialogWrapper {
        private final Dependency dependency;
        private final JBLabel newestLabel = new JBLabel("fetching...");

        PickableDetailsDialog(@NotNull Dependency dependency) {
            super(dependencyPicker, false);
            this.dependency = dependency;
            setTitle("Dependency Details");
            setOKButtonText("Close");
            init();
            fetchNewestVersion();
        }

        @Override
        protected Action @NotNull [] createActions() {
            return new Action[]{getOKAction()};
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            JPanel panel = new JPanel(new GridBagLayout());
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = JBUI.insets(3, 4);
            gbc.anchor = GridBagConstraints.WEST;
            gbc.fill = GridBagConstraints.HORIZONTAL;

            int row = 0;
            addRow(panel, gbc, row++, "Name:", DependencyPickerTable.displayName(dependency));
            addRow(panel, gbc, row++, "Group id:", dependency.getGroupId());
            addRow(panel, gbc, row++, "Artifact id:", dependency.getArtifactId());
            addRow(panel, gbc, row++, "Catalog version:",
                    dependency.getVersion().isEmpty() ? "(newest when added)" : dependency.getVersion());

            gbc.gridy = row;
            gbc.gridx = 0;
            gbc.weightx = 0;
            panel.add(new JBLabel("Newest version:"), gbc);
            gbc.gridx = 1;
            gbc.weightx = 1;
            newestLabel.setFont(newestLabel.getFont().deriveFont(Font.BOLD));
            panel.add(newestLabel, gbc);
            return panel;
        }

        private void addRow(@NotNull JPanel panel, @NotNull GridBagConstraints gbc, int row,
                            @NotNull String label, @NotNull String value) {
            gbc.gridy = row;
            gbc.gridx = 0;
            gbc.weightx = 0;
            panel.add(new JBLabel(label), gbc);
            gbc.gridx = 1;
            gbc.weightx = 1;
            JBLabel valueLabel = new JBLabel(value);
            valueLabel.setFont(valueLabel.getFont().deriveFont(Font.BOLD));
            panel.add(valueLabel, gbc);
        }

        private void fetchNewestVersion() {
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                String latest = MavenCentralClient.getLatestVersion(
                        dependency.getGroupId(), dependency.getArtifactId());
                ApplicationManager.getApplication().invokeLater(() -> newestLabel.setText(
                        latest == null ? "? (Maven Central not reachable or artifact unknown)" : latest));
            });
        }
    }
}

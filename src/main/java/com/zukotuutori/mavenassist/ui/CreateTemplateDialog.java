package com.zukotuutori.mavenassist.ui;

import com.zukotuutori.mavenassist.model.Dependency;
import com.zukotuutori.mavenassist.services.DependencyManagerService;
import com.zukotuutori.mavenassist.services.PomTemplateService;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.io.File;
import java.util.List;

/**
 * Creates a reusable pom template. Offers the same choices as the pom-creation view:
 * coordinates, an optional parent pom, the newest-version flag and the dependencies.
 * plus the name the template is stored under.
 */
class CreateTemplateDialog extends DialogWrapper {

    private final Project project;

    private final JTextField nameField = new JTextField();
    private final JTextField groupIdField = new JTextField("com.example");
    private final JTextField artifactIdField = new JTextField("my-app");
    private final JTextField versionField = new JTextField("1.0.0");
    private final JTextField parentPomField = new JTextField();
    private final JBCheckBox newestVersionsCheck =
            new JBCheckBox("Always use the newest version of each dependency (from Maven Central)", true);
    private final SearchTextField searchField = new SearchTextField();
    // No delete column: removing catalog entries is out of place while composing a template.
    private final DependencyPickerTable picker =
            new DependencyPickerTable(this::showDependencyDetails, false, false);
    /** The template being edited, or null when creating a new one. */
    private final PomTemplateService.@Nullable Template editing;

    CreateTemplateDialog(@NotNull Project project) {
        this(project, null);
    }

    CreateTemplateDialog(@NotNull Project project, PomTemplateService.@Nullable Template editing) {
        super(project);
        this.project = project;
        this.editing = editing;
        setTitle(editing == null ? "Create Template" : "Edit Template");
        setOKButtonText("Save Template");
        init();
        picker.setDependencies(DependencyManagerService.getInstance().getAllDependencies());
        picker.bindSearch(searchField);
        if (editing != null) {
            nameField.setText(editing.name);
            groupIdField.setText(editing.groupId);
            artifactIdField.setText(editing.artifactId);
            versionField.setText(editing.version);
            parentPomField.setText(PomTemplateService.parentReference(editing));
            newestVersionsCheck.setSelected(editing.useNewestVersions);
            for (String gav : editing.dependencies) {
                Dependency d = Dependency.fromGav(gav);
                if (d != null) {
                    picker.setChecked(d.getKey(), true);
                }
            }
        }
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        panel.setPreferredSize(new Dimension(JBUI.scale(540), JBUI.scale(460)));
        panel.add(buildFormPanel(), BorderLayout.NORTH);
        panel.add(buildDependencyPanel(), BorderLayout.CENTER);
        return panel;
    }

    private @NotNull JPanel buildFormPanel() {
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = JBUI.insets(2, 2);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;
        addRow(form, gbc, row++, "Template name:", nameField);
        addRow(form, gbc, row++, "Group id:", groupIdField);
        addRow(form, gbc, row++, "Artifact id:", artifactIdField);
        addRow(form, gbc, row++, "Version:", versionField);

        gbc.gridy = row++;
        gbc.gridx = 0;
        gbc.weightx = 0;
        form.add(new JBLabel("Parent pom path / GAV (optional):"), gbc);

        JPanel parentPanel = new JPanel(new BorderLayout(JBUI.scale(4), 0));
        parentPanel.add(parentPomField, BorderLayout.CENTER);
        JButton browseParent = new JButton("Choose Path...");
        browseParent.addActionListener(e -> chooseParentPom());
        parentPanel.add(browseParent, BorderLayout.EAST);
        gbc.gridx = 1;
        gbc.weightx = 1;
        form.add(parentPanel, gbc);

        gbc.gridy = row;
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        form.add(newestVersionsCheck, gbc);
        gbc.gridwidth = 1;
        return form;
    }

    private void addRow(@NotNull JPanel form, @NotNull GridBagConstraints gbc, int row,
                        @NotNull String label, @NotNull JTextField field) {
        gbc.gridy = row;
        gbc.gridx = 0;
        gbc.weightx = 0;
        form.add(new JBLabel(label), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        form.add(field, gbc);
    }

    private @NotNull JPanel buildDependencyPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        JPanel header = new JPanel(new BorderLayout(0, JBUI.scale(2)));
        header.add(new JBLabel("Tick the dependencies this template should contain:"), BorderLayout.NORTH);

        JPanel searchRow = new JPanel(new BorderLayout(JBUI.scale(4), 0));
        searchRow.add(searchField, BorderLayout.CENTER);
        JButton selectAllButton = new JButton("Select All");
        selectAllButton.setToolTipText("Tick every dependency currently shown (search applied)");
        selectAllButton.addActionListener(e -> picker.checkAllVisible());
        searchRow.add(selectAllButton, BorderLayout.EAST);
        header.add(searchRow, BorderLayout.SOUTH);

        panel.add(header, BorderLayout.NORTH);
        panel.add(new JBScrollPane(picker), BorderLayout.CENTER);
        return panel;
    }

    private void showDependencyDetails(@NotNull Dependency dependency) {
        Messages.showInfoMessage(getContentPanel(), dependency.getDisplayName(), "Dependency");
    }

    private void chooseParentPom() {
        FileChooserDescriptor descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("xml")
                .withTitle("Choose Parent pom.xml");
        VirtualFile chosen = FileChooser.chooseFile(descriptor, project, null);
        if (chosen != null) {
            parentPomField.setText(chosen.getPath());
        }
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        String name = nameField.getText().trim();
        if (name.isEmpty()) {
            return new ValidationInfo("Give the template a name.", nameField);
        }
        boolean keepingOwnName = editing != null && name.equalsIgnoreCase(editing.name);
        if (!keepingOwnName && PomTemplateService.getInstance().exists(name)) {
            return new ValidationInfo("A template named \"" + name + "\" already exists.", nameField);
        }
        if (groupIdField.getText().trim().isEmpty()) {
            return new ValidationInfo("Group id must not be empty.", groupIdField);
        }
        if (artifactIdField.getText().trim().isEmpty()) {
            return new ValidationInfo("Artifact id must not be empty.", artifactIdField);
        }
        if (versionField.getText().trim().isEmpty()) {
            return new ValidationInfo("Version must not be empty.", versionField);
        }
        String parentPath = parentPomField.getText().trim();
        if (!parentPath.isEmpty() && !new File(parentPath).isFile()
                && PomTemplateService.parseParentCoordinates(parentPath) == null) {
            return new ValidationInfo("Use an existing pom.xml path or groupId:artifactId:version.",
                    parentPomField);
        }
        return null;
    }

    /** The template the user configured. Only valid after the dialog was closed with OK. */
    @NotNull PomTemplateService.Template getTemplate() {
        PomTemplateService.Template template = new PomTemplateService.Template();
        template.name = nameField.getText().trim();
        template.groupId = groupIdField.getText().trim();
        template.artifactId = artifactIdField.getText().trim();
        template.version = versionField.getText().trim();
        PomTemplateService.setParentReference(template, parentPomField.getText());
        template.useNewestVersions = newestVersionsCheck.isSelected();
        List<Dependency> checked = picker.getCheckedDependencies();
        for (Dependency dependency : checked) {
            template.dependencies.add(dependency.toTemplateCoordinate());
        }
        return template;
    }
}

package com.zukotuutori.mavenassist.ui;

import com.zukotuutori.mavenassist.services.PomTemplateService;
import com.zukotuutori.mavenassist.services.PomTemplateService.Template;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Lets the user pick one of the stored pom templates, create a new one, or delete one.
 * Closing with OK applies the selected template to the pom-creation view.
 */
class ChooseTemplateDialog extends DialogWrapper {

    private final Project project;
    private final DefaultListModel<Template> model = new DefaultListModel<>();
    private final JBList<Template> list = new JBList<>(model);

    ChooseTemplateDialog(@NotNull Project project) {
        super(project);
        this.project = project;
        setTitle("Choose Template");
        setOKButtonText("Apply");
        init();
        reload(null);
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        panel.setPreferredSize(new Dimension(JBUI.scale(460), JBUI.scale(320)));

        panel.add(new JBLabel("Pick a template to fill in the pom form:"), BorderLayout.NORTH);

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(SimpleListCellRenderer.create("", ChooseTemplateDialog::describe));
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && list.getSelectedValue() != null) {
                    doOKAction();
                }
            }
        });
        panel.add(new JBScrollPane(list), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
        JButton createButton = new JButton("Create Template...");
        createButton.addActionListener(e -> createTemplate());
        buttons.add(createButton);
        JButton editButton = new JButton("Edit...");
        editButton.setToolTipText("Change the selected template");
        editButton.addActionListener(e -> editSelected());
        buttons.add(editButton);
        // Same red pill as the delete buttons in the dependency table.
        JButton deleteButton = new JButton("Delete", TablePills.TRASH_WHITE);
        deleteButton.putClientProperty("JButton.backgroundColor", TablePills.DELETE);
        deleteButton.setForeground(Color.WHITE);
        deleteButton.setToolTipText("Delete the selected template permanently");
        deleteButton.addActionListener(e -> deleteSelected());
        buttons.add(deleteButton);
        panel.add(buttons, BorderLayout.SOUTH);

        return panel;
    }

    /** "Name — com.example:my-app:1.0.0 · 3 dependencies" */
    private static @NotNull String describe(@NotNull Template template) {
        int count = template.dependencies == null ? 0 : template.dependencies.size();
        return template.name + "  —  "
                + template.groupId + ":" + template.artifactId + ":" + template.version
                + "  ·  " + count + (count == 1 ? " dependency" : " dependencies");
    }

    private void createTemplate() {
        CreateTemplateDialog dialog = new CreateTemplateDialog(project);
        if (dialog.showAndGet()) {
            Template created = dialog.getTemplate();
            PomTemplateService.getInstance().save(created);
            reload(created.name);
        }
    }

    /** Opens the template form prefilled; saving replaces the template (renames included). */
    private void editSelected() {
        Template selected = list.getSelectedValue();
        if (selected == null) {
            return;
        }
        CreateTemplateDialog dialog = new CreateTemplateDialog(project, selected);
        if (dialog.showAndGet()) {
            Template updated = dialog.getTemplate();
            PomTemplateService service = PomTemplateService.getInstance();
            if (!updated.name.equalsIgnoreCase(selected.name)) {
                service.remove(selected.name);
            }
            service.save(updated);
            reload(updated.name);
        }
    }

    private void deleteSelected() {
        Template selected = list.getSelectedValue();
        if (selected == null) {
            return;
        }
        int answer = Messages.showYesNoDialog(getContentPanel(),
                "Delete the template \"" + selected.name + "\"?",
                "Delete Template", Messages.getWarningIcon());
        if (answer == Messages.YES) {
            PomTemplateService.getInstance().remove(selected.name);
            reload(null);
        }
    }

    /** Refills the list from the service, re-selecting the template with the given name if present. */
    private void reload(@Nullable String selectName) {
        model.clear();
        for (Template template : PomTemplateService.getInstance().getTemplates()) {
            model.addElement(template);
        }
        if (selectName != null) {
            for (int i = 0; i < model.size(); i++) {
                if (model.get(i).name.equalsIgnoreCase(selectName)) {
                    list.setSelectedIndex(i);
                    return;
                }
            }
        }
        if (!model.isEmpty()) {
            list.setSelectedIndex(0);
        }
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        if (model.isEmpty()) {
            return new ValidationInfo("No templates yet — press \"Create Template...\" to add one.", list);
        }
        if (list.getSelectedValue() == null) {
            return new ValidationInfo("Select a template.", list);
        }
        return null;
    }

    /** The chosen template. Only valid after the dialog was closed with OK. */
    @Nullable Template getSelectedTemplate() {
        return list.getSelectedValue();
    }
}

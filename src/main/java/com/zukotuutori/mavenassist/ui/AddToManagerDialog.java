package com.zukotuutori.mavenassist.ui;

import com.zukotuutori.mavenassist.maven.PomUtils;
import com.zukotuutori.mavenassist.model.Dependency;
import com.zukotuutori.mavenassist.services.DependencyManagerService;
import com.zukotuutori.mavenassist.util.Notifier;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.List;

/**
 * Dialog to permanently add dependencies to the manager: enter a display name and
 * paste the dependency code — one or more &lt;dependency&gt; XML blocks or plain
 * "groupId:artifactId:version" lines.
 */
public class AddToManagerDialog extends DialogWrapper {

    private final Project project;
    private final JTextField nameField = new JTextField();
    private final JBTextArea textArea = new JBTextArea();

    public AddToManagerDialog(@Nullable Project project) {
        super(project, true);
        this.project = project;
        setTitle("Add Dependencies to Manager");
        setOKButtonText("Add");
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        panel.setPreferredSize(new Dimension(JBUI.scale(520), JBUI.scale(360)));

        JPanel north = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        JPanel nameRow = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        nameRow.add(new JBLabel("Name:"), BorderLayout.WEST);
        nameRow.add(nameField, BorderLayout.CENTER);
        north.add(nameRow, BorderLayout.NORTH);
        north.add(new JBLabel("<html>Dependency code — one or more <b>&lt;dependency&gt;</b> XML blocks<br/>"
                + "(as shown on mvnrepository.com) or plain <b>groupId:artifactId[:version]</b> lines:</html>"),
                BorderLayout.SOUTH);
        panel.add(north, BorderLayout.NORTH);

        textArea.setLineWrap(false);
        panel.add(new JBScrollPane(textArea), BorderLayout.CENTER);
        return panel;
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return nameField;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        String name = nameField.getText().trim();
        if (name.isEmpty()) {
            return new ValidationInfo("Please enter a name for the dependency.", nameField);
        }
        if (DependencyManagerService.getInstance().isNameUsed(name)) {
            return new ValidationInfo("The name \"" + name + "\" is already used in the manager.", nameField);
        }
        if (PomUtils.parsePastedDependencies(textArea.getText()).isEmpty()) {
            return new ValidationInfo("No dependency found. Paste a <dependency> block or a groupId:artifactId line.",
                    textArea);
        }
        return null;
    }

    @Override
    protected void doOKAction() {
        List<Dependency> parsed = PomUtils.parsePastedDependencies(textArea.getText());
        String baseName = nameField.getText().trim();
        DependencyManagerService service = DependencyManagerService.getInstance();
        int added = 0;
        int skipped = 0;
        for (int i = 0; i < parsed.size(); i++) {
            Dependency d = parsed.get(i);
            // If several blocks were pasted at once, number the name to keep entries distinguishable.
            String name = parsed.size() == 1 ? baseName : baseName + " " + (i + 1);
            if (service.addCustomDependency(new Dependency(d.getGroupId(), d.getArtifactId(), d.getVersion(), name))) {
                added++;
            } else {
                skipped++;
            }
        }
        Notifier.info(project, "Added " + added + " dependencies to the manager"
                + (skipped > 0 ? " (" + skipped + " skipped — name already used)" : "") + ".");
        super.doOKAction();
    }
}

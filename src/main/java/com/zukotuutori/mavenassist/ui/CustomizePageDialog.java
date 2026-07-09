package com.zukotuutori.mavenassist.ui;

import com.zukotuutori.mavenassist.services.UiCustomizationService;
import com.zukotuutori.mavenassist.services.UiCustomizationService.Field;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.util.EnumMap;
import java.util.Map;

/**
 * Chooses, per dependency field, whether it appears directly in the table or only
 * behind the "Details" button. The choice is applied when the dialog is closed with OK.
 */
class CustomizePageDialog extends DialogWrapper {

    private final Map<Field, JBCheckBox> inTable = new EnumMap<>(Field.class);
    private final Map<Field, JBCheckBox> inDetails = new EnumMap<>(Field.class);

    CustomizePageDialog(@NotNull Project project) {
        super(project);
        setTitle("Visible Fields");
        setOKButtonText("Save");
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        UiCustomizationService service = UiCustomizationService.getInstance();

        JPanel grid = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = JBUI.insets(3, 6);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridy = 0;
        gbc.gridx = 0;
        grid.add(header("Field"), gbc);
        gbc.gridx = 1;
        grid.add(header("In table"), gbc);
        gbc.gridx = 2;
        grid.add(header("In details"), gbc);

        int row = 1;
        for (Field field : Field.values()) {
            gbc.gridy = row++;
            gbc.gridx = 0;
            gbc.weightx = 1;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            grid.add(new JBLabel(field.displayName), gbc);

            gbc.weightx = 0;
            gbc.fill = GridBagConstraints.NONE;
            gbc.anchor = GridBagConstraints.CENTER;

            JBCheckBox table = new JBCheckBox("", service.isShownInTable(field));
            inTable.put(field, table);
            gbc.gridx = 1;
            grid.add(table, gbc);

            JBCheckBox details = new JBCheckBox("", service.isShownInDetails(field));
            inDetails.put(field, details);
            gbc.gridx = 2;
            grid.add(details, gbc);

            gbc.anchor = GridBagConstraints.WEST;
        }

        JBLabel hint = new JBLabel("A field hidden from the table is still reachable through the "
                + "Details button on each row.");
        hint.setForeground(UIUtil.getContextHelpForeground());

        JButton resetButton = new JButton("Reset to Defaults");
        resetButton.addActionListener(e -> resetToDefaults());
        JPanel resetRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        resetRow.add(resetButton);

        JPanel south = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        south.add(hint, BorderLayout.NORTH);
        south.add(resetRow, BorderLayout.SOUTH);

        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(10)));
        panel.setBorder(JBUI.Borders.empty(4));
        panel.add(grid, BorderLayout.CENTER);
        panel.add(south, BorderLayout.SOUTH);
        return panel;
    }

    private static @NotNull JBLabel header(@NotNull String text) {
        JBLabel label = new JBLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        return label;
    }

    /** Ticks the factory defaults; nothing is saved until OK. */
    private void resetToDefaults() {
        for (Field field : Field.values()) {
            inTable.get(field).setSelected(field.defaultInTable);
            inDetails.get(field).setSelected(field.defaultInDetails);
        }
    }

    /** At least one field has to stay in the table, otherwise it would render without any columns. */
    @Override
    protected @Nullable ValidationInfo doValidate() {
        for (Field field : Field.values()) {
            if (inTable.get(field).isSelected()) {
                return null;
            }
        }
        return new ValidationInfo("Keep at least one field in the table.");
    }

    @Override
    protected void doOKAction() {
        UiCustomizationService service = UiCustomizationService.getInstance();
        for (Field field : Field.values()) {
            service.setShownInTable(field, inTable.get(field).isSelected());
            service.setShownInDetails(field, inDetails.get(field).isSelected());
        }
        service.notifyCustomizationChanged();
        super.doOKAction();
    }
}

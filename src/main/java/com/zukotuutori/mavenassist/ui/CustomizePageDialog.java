package com.zukotuutori.mavenassist.ui;

import com.zukotuutori.mavenassist.actions.RunAutoUpdateAction;
import com.zukotuutori.mavenassist.services.AutoUpdateSettings;
import com.zukotuutori.mavenassist.services.AutoUpdateSettings.Trigger;
import com.zukotuutori.mavenassist.services.UiCustomizationService;
import com.zukotuutori.mavenassist.services.UiCustomizationService.Field;
import com.intellij.openapi.actionSystem.KeyboardShortcut;
import com.intellij.openapi.actionSystem.Shortcut;
import com.intellij.openapi.keymap.Keymap;
import com.intellij.openapi.keymap.KeymapManager;
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
import javax.swing.KeyStroke;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.util.EnumMap;
import java.util.Map;

/**
 * Controls dependency-field placement, auto-update triggers, optional non-major update
 * confirmations and the custom shortcut. Applied when the dialog is closed with OK.
 */
class CustomizePageDialog extends DialogWrapper {

    private final Map<Field, JBCheckBox> inTable = new EnumMap<>(Field.class);
    private final Map<Field, JBCheckBox> inDetails = new EnumMap<>(Field.class);
    private final Map<Trigger, JBCheckBox> triggers = new EnumMap<>(Trigger.class);
    private JBCheckBox confirmNonMajorUpdates;
    /** Created together with the trigger rows; null only before the panel is built. */
    private ShortcutField shortcutField;

    CustomizePageDialog(@NotNull Project project) {
        super(project);
        setTitle("Customize Maven Assist");
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

        JPanel fields = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        fields.add(grid, BorderLayout.CENTER);
        fields.add(hint, BorderLayout.SOUTH);

        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(10)));
        panel.setBorder(JBUI.Borders.empty(4));
        panel.add(fields, BorderLayout.NORTH);
        panel.add(buildTriggerPanel(), BorderLayout.CENTER);
        panel.add(resetRow, BorderLayout.SOUTH);
        return panel;
    }

    /** "Auto update runs..." describes the moments at which flagged dependencies are checked. */
    private @NotNull JPanel buildTriggerPanel() {
        AutoUpdateSettings service = AutoUpdateSettings.getInstance();

        JPanel list = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = JBUI.insets(1, 6);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.gridx = 0;
        gbc.weightx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;
        gbc.gridy = row++;
        list.add(header("Run \"auto update\" ..."), gbc);

        for (Trigger trigger : Trigger.values()) {
            JBCheckBox box = new JBCheckBox(trigger.displayName, service.isTriggerEnabled(trigger));
            triggers.put(trigger, box);
            gbc.gridy = row++;
            list.add(box, gbc);

            // The shortcut trigger carries one extra row: the key combination itself.
            if (trigger == Trigger.ON_SHORTCUT) {
                gbc.gridy = row++;
                gbc.insets = JBUI.insets(1, 28, 1, 6);
                list.add(buildShortcutRow(box), gbc);
                gbc.insets = JBUI.insets(1, 6);
            }
        }

        gbc.gridy = row++;
        gbc.insets = JBUI.insets(8, 6, 1, 6);
        list.add(header("Auto update confirmations"), gbc);

        confirmNonMajorUpdates = new JBCheckBox(
                "Ask before minor and patch auto updates",
                service.isConfirmNonMajorUpdates());
        confirmNonMajorUpdates.setToolTipText(
                "When disabled, only major auto updates require confirmation.");
        gbc.gridy = row++;
        gbc.insets = JBUI.insets(1, 6);
        list.add(confirmNonMajorUpdates, gbc);

        JBLabel confirmationHint = new JBLabel(
                "Major auto updates always require confirmation and cannot be disabled here.");
        confirmationHint.setForeground(UIUtil.getContextHelpForeground());
        gbc.gridy = row++;
        gbc.insets = JBUI.insets(1, 28, 1, 6);
        list.add(confirmationHint, gbc);

        JBLabel hint = new JBLabel("<html>Only dependencies with the green <b>auto update</b> flag are "
                + "touched. Tick nothing to update only by hand.</html>");
        hint.setForeground(UIUtil.getContextHelpForeground());
        gbc.gridy = row;
        gbc.insets = JBUI.insets(6, 6, 0, 6);
        list.add(hint, gbc);

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(JBUI.Borders.emptyTop(10));
        panel.add(list, BorderLayout.CENTER);
        return panel;
    }

    /**
     * The "Shortcut:" row under the shortcut trigger. It is only usable while that trigger is
     * ticked, so the checkbox above it stays the single switch.
     */
    private @NotNull JPanel buildShortcutRow(@NotNull JBCheckBox trigger) {
        shortcutField = new ShortcutField(currentShortcut());

        JButton clear = new JButton("Clear");
        clear.setToolTipText("Remove the key combination again");
        clear.addActionListener(e -> shortcutField.clear());

        JPanel row = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        JBLabel label = new JBLabel("Shortcut:");
        label.setBorder(JBUI.Borders.emptyRight(6));
        row.add(label, BorderLayout.WEST);
        row.add(shortcutField, BorderLayout.CENTER);
        row.add(clear, BorderLayout.EAST);

        Runnable sync = () -> {
            boolean on = trigger.isSelected();
            row.setVisible(on);
            shortcutField.setEnabled(on);
            clear.setEnabled(on);
        };
        trigger.addActionListener(e -> sync.run());
        sync.run();
        return row;
    }

    /** The combination currently bound to the action in the IDE's active keymap, if any. */
    private static @Nullable KeyStroke currentShortcut() {
        for (Shortcut shortcut : KeymapManager.getInstance().getActiveKeymap()
                .getShortcuts(RunAutoUpdateAction.ID)) {
            if (shortcut instanceof KeyboardShortcut keyboard) {
                return keyboard.getFirstKeyStroke();
            }
        }
        return null;
    }

    /**
     * Writes the recorded combination into the active keymap, replacing whatever this action
     * had before. The keymap is the right owner: the shortcut then shows up under
     * Settings | Keymap like any other, and the platform persists it.
     */
    private void applyShortcut() {
        Keymap keymap = KeymapManager.getInstance().getActiveKeymap();
        for (Shortcut existing : keymap.getShortcuts(RunAutoUpdateAction.ID)) {
            keymap.removeShortcut(RunAutoUpdateAction.ID, existing);
        }
        KeyStroke keyStroke = shortcutField == null ? null : shortcutField.getKeyStroke();
        if (keyStroke != null && triggers.get(Trigger.ON_SHORTCUT).isSelected()) {
            keymap.addShortcut(RunAutoUpdateAction.ID, new KeyboardShortcut(keyStroke, null));
        }
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
        for (Trigger trigger : Trigger.values()) {
            triggers.get(trigger).setSelected(trigger.enabledByDefault);
        }
        if (confirmNonMajorUpdates != null) {
            confirmNonMajorUpdates.setSelected(true);
        }
        if (shortcutField != null) {
            shortcutField.clear();
        }
    }

    /** At least one field has to stay in the table, otherwise it would render without any columns. */
    @Override
    protected @Nullable ValidationInfo doValidate() {
        JBCheckBox shortcutTrigger = triggers.get(Trigger.ON_SHORTCUT);
        if (shortcutTrigger != null && shortcutTrigger.isSelected()
                && shortcutField != null && shortcutField.getKeyStroke() == null) {
            return new ValidationInfo("Press a key combination for the shortcut trigger, "
                    + "or untick it.", shortcutField);
        }
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
        AutoUpdateSettings autoUpdate = AutoUpdateSettings.getInstance();
        for (Trigger trigger : Trigger.values()) {
            autoUpdate.setTriggerEnabled(trigger, triggers.get(trigger).isSelected());
        }
        autoUpdate.setConfirmNonMajorUpdates(
                confirmNonMajorUpdates == null || confirmNonMajorUpdates.isSelected());
        applyShortcut();
        service.notifyCustomizationChanged();
        super.doOKAction();
    }
}

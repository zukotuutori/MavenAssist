package com.zukotuutori.mavenassist.ui;

import com.zukotuutori.mavenassist.services.UiCustomizationService;
import com.zukotuutori.mavenassist.services.UiCustomizationService.ButtonId;
import com.zukotuutori.mavenassist.services.UiCustomizationService.ButtonStyle;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.ColorPanel;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;

/**
 * Small floating (modeless) editor for one button, opened by clicking the button
 * in the customize window's preview. Every change — text, colors, height, font
 * size — is applied to the preview immediately; there is nothing to confirm here
 * (the customize window's Save/Cancel decides whether the changes are kept).
 */
public class ButtonStyleDialog extends DialogWrapper {

    private final ButtonId id;

    private final JBTextField textField = new JBTextField();
    private final ColorPanel backgroundPanel = new ColorPanel();
    private final ColorPanel foregroundPanel = new ColorPanel();
    private final JSpinner widthSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 600, 1));
    private final JSpinner heightSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 200, 1));
    private final JSpinner fontSizeSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 72, 1));

    /** Guards against control listeners re-applying while the form is being loaded. */
    private boolean loading;

    /** @param parent a showing component of the customize window, used as the dialog's owner */
    public ButtonStyleDialog(@NotNull java.awt.Component parent, @NotNull ButtonId id) {
        super(parent, false);
        this.id = id;
        setModal(false);
        setTitle("Style: " + id.defaultText);
        setOKButtonText("Close");
        init();
        loadForm();
        wireListeners();
    }

    @Override
    protected Action @NotNull [] createActions() {
        return new Action[]{getOKAction()};
    }

    @Override
    protected Action @NotNull [] createLeftSideActions() {
        return new Action[]{new AbstractAction("Reset This Button") {
            @Override
            public void actionPerformed(ActionEvent e) {
                resetButton();
            }
        }};
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = JBUI.insets(4);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;
        addFormRow(form, gbc, row++, "Text:", textField);
        addFormRow(form, gbc, row++, "Background color:", withDefaultReset(backgroundPanel));
        addFormRow(form, gbc, row++, "Text color:", withDefaultReset(foregroundPanel));
        addFormRow(form, gbc, row++, "Width (px, 0 = full width):", widthSpinner);
        addFormRow(form, gbc, row++, "Height (px, 0 = default):", heightSpinner);
        addFormRow(form, gbc, row++, "Font size (pt, 0 = default):", fontSizeSpinner);

        JBLabel hint = new JBLabel("<html>Changes are applied immediately.<br>"
                + "Tip: you can also resize the button by dragging its right/bottom edge in the preview.</html>");
        hint.setFont(hint.getFont().deriveFont(Font.ITALIC));
        gbc.gridy = row;
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        form.add(hint, gbc);
        return form;
    }

    private void addFormRow(@NotNull JPanel form, @NotNull GridBagConstraints gbc, int row,
                            @NotNull String label, @NotNull JComponent component) {
        gbc.gridy = row;
        gbc.gridwidth = 1;
        gbc.gridx = 0;
        gbc.weightx = 0;
        form.add(new JBLabel(label), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        form.add(component, gbc);
    }

    /** A color swatch plus a small "Default" button that clears the custom color. */
    private @NotNull JPanel withDefaultReset(@NotNull ColorPanel colorPanel) {
        JPanel panel = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        panel.add(colorPanel, BorderLayout.WEST);
        JButton reset = new JButton("Default");
        reset.addActionListener(e -> {
            colorPanel.setSelectedColor(null);
            applyForm();
        });
        panel.add(reset, BorderLayout.CENTER);
        return panel;
    }

    private void wireListeners() {
        textField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                applyForm();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                applyForm();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                applyForm();
            }
        });
        backgroundPanel.addActionListener(e -> applyForm());
        foregroundPanel.addActionListener(e -> applyForm());
        widthSpinner.addChangeListener(e -> applyForm());
        heightSpinner.addChangeListener(e -> applyForm());
        fontSizeSpinner.addChangeListener(e -> applyForm());
    }

    private void loadForm() {
        loading = true;
        try {
            ButtonStyle style = UiCustomizationService.getInstance().getStyle(id);
            textField.setText(style.text);
            textField.getEmptyText().setText(id.defaultText);
            backgroundPanel.setSelectedColor(UiCustomizationService.parseColor(style.background));
            foregroundPanel.setSelectedColor(UiCustomizationService.parseColor(style.foreground));
            widthSpinner.setValue(Math.max(0, style.width));
            heightSpinner.setValue(Math.max(0, style.height));
            fontSizeSpinner.setValue(Math.max(0, style.fontSize));
        } finally {
            loading = false;
        }
    }

    /** Saves the form into the service and refreshes the live page. */
    private void applyForm() {
        if (loading) {
            return;
        }
        UiCustomizationService service = UiCustomizationService.getInstance();
        ButtonStyle style = service.getStyle(id);
        style.text = textField.getText().trim();
        style.background = UiCustomizationService.toHex(backgroundPanel.getSelectedColor());
        style.foreground = UiCustomizationService.toHex(foregroundPanel.getSelectedColor());
        style.width = (Integer) widthSpinner.getValue();
        style.height = (Integer) heightSpinner.getValue();
        style.fontSize = (Integer) fontSizeSpinner.getValue();
        service.setStyle(id, style);
        service.notifyCustomizationChanged();
    }

    /** Back to the default look — the button's position (section and drag order) is kept. */
    private void resetButton() {
        UiCustomizationService service = UiCustomizationService.getInstance();
        ButtonStyle old = service.getStyle(id);
        ButtonStyle cleared = new ButtonStyle();
        cleared.order = old.order;
        cleared.container = old.container;
        service.setStyle(id, cleared);
        service.notifyCustomizationChanged();
        loadForm();
    }
}

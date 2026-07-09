package com.zukotuutori.mavenassist.ui;

import com.zukotuutori.mavenassist.services.UiCustomizationService;
import com.zukotuutori.mavenassist.services.UiCustomizationService.ButtonId;
import com.zukotuutori.mavenassist.services.UiCustomizationService.Field;
import com.zukotuutori.mavenassist.services.UiCustomizationService.Group;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The separate "Customize" window: shows a live preview of the whole plugin page
 * (top bar, dependency table with sample data, action buttons, details-dialog
 * buttons). All editing happens here — drag a preview button to move it, click one
 * to open the style editor, tick field checkboxes on the right. The real tool
 * window is untouched until <b>Save</b>; <b>Cancel</b> throws the changes away.
 */
public class CustomizePageDialog extends DialogWrapper {

    private static final JBColor AUTO_ON = new JBColor(new Color(0x2E8B44), new Color(0x5FBF75));
    private static final JBColor AUTO_OFF = new JBColor(new Color(0xC0392B), new Color(0xE06C5F));

    /** Sample dependencies shown in the preview table. */
    private static final String[][] SAMPLE_ROWS = {
            {"Guava", "com.google.guava", "guava", "33.0.0-jre", "33.4.8-jre", "true"},
            {"Jackson Databind", "com.fasterxml.jackson.core", "jackson-databind", "2.17.0", "2.19.1", "false"},
            {"JUnit Jupiter", "org.junit.jupiter", "junit-jupiter", "5.10.2", "5.13.3", "true"},
    };

    private final UiCustomizationService.State snapshot;
    private final Runnable changeListener = this::onCustomizationChanged;
    private final JPanel previewHost = new JPanel(new BorderLayout());
    private final Map<Field, JBCheckBox> inTableChecks = new EnumMap<>(Field.class);
    private final Map<Field, JBCheckBox> inDetailsChecks = new EnumMap<>(Field.class);

    private List<Field> previewFields;
    private boolean finished;

    public CustomizePageDialog(@Nullable Project project) {
        super(project, true);
        UiCustomizationService service = UiCustomizationService.getInstance();
        snapshot = service.copyCurrentState();
        service.setEditMode(true);
        setModal(false);
        setTitle("Customize Maven Assist Page");
        setOKButtonText("Save");
        init();
        service.addChangeListener(changeListener);
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel root = new JPanel(new BorderLayout(JBUI.scale(12), JBUI.scale(8)));

        JBLabel hint = new JBLabel("<html><b>Drag</b> a button anywhere — it follows your mouse and can be dropped "
                + "into any section (Directory card, Details dialog). <b>Drag its right/bottom edge</b> to "
                + "resize it. <b>Click</b> it to change text, colors and size. Nothing touches the real window "
                + "until you press <b>Save</b>.</html>");
        root.add(hint, BorderLayout.NORTH);

        previewHost.setBorder(BorderFactory.createTitledBorder("Preview"));
        rebuildPreview();
        root.add(previewHost, BorderLayout.CENTER);

        root.add(buildSidePanel(), BorderLayout.EAST);
        root.setPreferredSize(new Dimension(JBUI.scale(760), JBUI.scale(560)));
        return root;
    }

    // ---------------------------------------------------------------- preview

    private void onCustomizationChanged() {
        rebuildPreview();
        refreshCheckboxes();
    }

    private void rebuildPreview() {
        previewHost.removeAll();
        List<PreviewLayerUI.DropStack> stacks = new java.util.ArrayList<>();
        JBScrollPane scroll = new JBScrollPane(buildPreviewPage(stacks));
        scroll.setBorder(JBUI.Borders.empty());
        // The layer draws the drag ghost and animates the landing gaps between sections.
        previewHost.add(new javax.swing.JLayer<>(scroll, new PreviewLayerUI(stacks)), BorderLayout.CENTER);
        previewHost.revalidate();
        previewHost.repaint();
    }

    /** A mock of the real plugin page; every button section is registered as a drop target. */
    private @NotNull JPanel buildPreviewPage(@NotNull List<PreviewLayerUI.DropStack> stacks) {
        UiCustomizationService service = UiCustomizationService.getInstance();
        previewFields = service.visibleTableFields();

        JPanel page = new JPanel();
        page.setLayout(new BoxLayout(page, BoxLayout.Y_AXIS));
        page.setBorder(JBUI.Borders.empty(10));

        // --- Directory card (holds the horizontal drop stack for the top bar) ---
        JPanel dirInner = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        dirInner.setOpaque(false);
        JPanel fieldRow = new JPanel(new BorderLayout(JBUI.scale(4), 0));
        fieldRow.setOpaque(false);
        JTextField directoryField = new JTextField("/path/to/my-project");
        directoryField.setEnabled(false);
        fieldRow.add(directoryField, BorderLayout.CENTER);
        fieldRow.add(mockButton(null, AllIcons.Nodes.Folder), BorderLayout.EAST);
        dirInner.add(fieldRow, BorderLayout.NORTH);
        dirInner.add(buttonStack(stacks, Group.DIRECTORY_BAR, false), BorderLayout.CENTER);
        addCard(page, new SectionBox("Directory", AllIcons.Nodes.Folder, dirInner), true);

        // --- pom.xml card ---
        JPanel pomInner = new JPanel(new java.awt.GridLayout(0, 1, 0, JBUI.scale(2)));
        pomInner.setOpaque(false);
        JBLabel pathLabel = new JBLabel("/path/to/my-project/pom.xml");
        pathLabel.setForeground(com.intellij.util.ui.UIUtil.getContextHelpForeground());
        pomInner.add(mockRow(pathLabel, mockButton("Open", AllIcons.Actions.MenuOpen)));
        pomInner.add(mockRow(new JBLabel("<html>Parent pom: <b>none</b></html>"),
                mockButton("Add Parent Pom...", null)));
        addCard(page, new SectionBox("pom.xml", AllIcons.FileTypes.Xml, pomInner), true);

        // --- Dependencies card: search + filter/delete, sample table, bottom buttons ---
        JPanel depInner = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        depInner.setOpaque(false);
        JPanel searchRow = new JPanel(new BorderLayout(JBUI.scale(4), 0));
        searchRow.setOpaque(false);
        JTextField searchMock = new JTextField("Search...");
        searchMock.setEnabled(false);
        searchRow.add(searchMock, BorderLayout.CENTER);
        JPanel searchButtons = new JPanel(new java.awt.GridLayout(1, 2, JBUI.scale(4), 0));
        searchButtons.setOpaque(false);
        searchButtons.add(mockButton(null, AllIcons.General.Filter));
        JButton trashMock = mockButton(null, TablePills.TRASH_WHITE);
        trashMock.putClientProperty("JButton.backgroundColor", TablePills.DELETE);
        searchButtons.add(trashMock);
        searchRow.add(searchButtons, BorderLayout.EAST);
        depInner.add(searchRow, BorderLayout.NORTH);

        JBScrollPane tableScroll = new JBScrollPane(buildPreviewTable());
        tableScroll.setBorder(JBUI.Borders.empty());
        tableScroll.setPreferredSize(new Dimension(JBUI.scale(200), JBUI.scale(130)));
        depInner.add(tableScroll, BorderLayout.CENTER);

        JPanel tableButtons = new JPanel(new java.awt.GridLayout(1, 3, JBUI.scale(4), 0));
        tableButtons.setOpaque(false);
        tableButtons.add(mockButton("Check for Updates", AllIcons.Actions.Refresh));
        tableButtons.add(mockButton("Update All", AllIcons.Actions.Download));
        tableButtons.add(mockButton("Add Dependencies", AllIcons.General.Add));
        depInner.add(tableButtons, BorderLayout.SOUTH);
        addCard(page, new SectionBox("Dependencies", AllIcons.Nodes.PpLib, depInner), true);

        // --- Details dialog card (vertical drop stack; grows with drag animations) ---
        addCard(page, new SectionBox("Buttons in the \"Details\" dialog",
                buttonStack(stacks, Group.DETAILS_DIALOG, true)), false);

        // --- bottom bar mock ---
        JPanel bottomRow = new JPanel(new BorderLayout());
        bottomRow.setOpaque(false);
        JBLabel customizeMock = new JBLabel("Customize...");
        customizeMock.setForeground(new JBColor(new Color(0x3574F0), new Color(0x548AF7)));
        bottomRow.add(customizeMock, BorderLayout.WEST);
        JButton mvnMock = mockButton("MVN Clean Install", null);
        mvnMock.putClientProperty("JButton.backgroundColor",
                new JBColor(new Color(0x3574F0), new Color(0x3574F0)));
        bottomRow.add(mvnMock, BorderLayout.EAST);
        capHeight(bottomRow);
        page.add(javax.swing.Box.createVerticalStrut(JBUI.scale(8)));
        page.add(bottomRow);

        return page;
    }

    /** Adds a section card to the page; capped cards keep their preferred height. */
    private static void addCard(@NotNull JPanel page, @NotNull SectionBox card, boolean capped) {
        card.setAlignmentX(0f);
        if (capped) {
            card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
        }
        page.add(card);
        page.add(javax.swing.Box.createVerticalStrut(JBUI.scale(8)));
    }

    /** A disabled look-alike of a fixed (non-customizable) button in the real window. */
    private static @NotNull JButton mockButton(@Nullable String text, @Nullable javax.swing.Icon icon) {
        JButton button = new JButton(text, icon);
        button.setEnabled(false);
        button.setMargin(JBUI.insets(2, 6));
        return button;
    }

    private static @NotNull JPanel mockRow(@NotNull JBLabel label, @NotNull JButton button) {
        JPanel row = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        row.setOpaque(false);
        row.add(label, BorderLayout.CENTER);
        JPanel east = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 0, 0));
        east.setOpaque(false);
        east.add(button);
        row.add(east, BorderLayout.EAST);
        return row;
    }

    /** One droppable section holding the buttons currently placed in it. */
    private static @NotNull JPanel buttonStack(@NotNull List<PreviewLayerUI.DropStack> stacks,
                                               @NotNull Group group, boolean vertical) {
        UiCustomizationService service = UiCustomizationService.getInstance();
        JPanel stack = new JPanel();
        stack.setLayout(new BoxLayout(stack, vertical ? BoxLayout.Y_AXIS : BoxLayout.X_AXIS));
        List<ButtonId> ids = service.buttonsIn(group);
        for (ButtonId id : ids) {
            stack.add(UiEditSupport.wrap(previewButton(id), vertical));
        }
        if (ids.isEmpty()) {
            JBLabel hint = new JBLabel("(drop buttons here)");
            hint.setFont(hint.getFont().deriveFont(Font.ITALIC));
            hint.setForeground(JBColor.GRAY);
            hint.setBorder(JBUI.Borders.empty(8));
            stack.add(hint);
        }
        if (vertical) {
            stack.setAlignmentX(0f);
        } else {
            capHeight(stack);
        }
        stacks.add(new PreviewLayerUI.DropStack(stack, group, vertical));
        return stack;
    }

    /** A styled button tagged for the drag layer: dragging moves it, clicking styles it. */
    private static @NotNull JButton previewButton(@NotNull ButtonId id) {
        JButton button = new JButton();
        UiCustomizationService.getInstance().applyTo(id, button);
        UiEditSupport.tag(button, id);
        return button;
    }

    /** Position of the Update column among the preview field columns. */
    private int previewUpdateColumn() {
        int version = previewFields.indexOf(Field.VERSION);
        return version >= 0 ? version + 1 : previewFields.size();
    }

    private @NotNull JBTable buildPreviewTable() {
        JBTable table = new JBTable(new PreviewTableModel());
        TablePills.modernize(table);
        int autoIndex = previewFields.indexOf(Field.AUTO_UPDATE);
        if (autoIndex >= 0) {
            int autoColumn = autoIndex < previewUpdateColumn() ? autoIndex : autoIndex + 1;
            table.getColumnModel().getColumn(autoColumn).setCellRenderer(new TablePills.AutoUpdateRenderer());
            table.getColumnModel().getColumn(autoColumn).setMaxWidth(JBUI.scale(110));
        }
        UpdateButtonRenderer updateRenderer = new UpdateButtonRenderer();
        table.getColumnModel().getColumn(previewUpdateColumn()).setCellRenderer(updateRenderer);
        table.getColumnModel().getColumn(previewUpdateColumn())
                .setMaxWidth(Math.max(JBUI.scale(90), updateRenderer.getPreferredSize().width + JBUI.scale(8)));
        int detailsColumn = previewFields.size() + 1;
        DetailsButtonRenderer renderer = new DetailsButtonRenderer();
        table.getColumnModel().getColumn(detailsColumn).setCellRenderer(renderer);
        table.getColumnModel().getColumn(detailsColumn)
                .setMaxWidth(Math.max(JBUI.scale(120), renderer.getPreferredSize().width + JBUI.scale(8)));
        int deleteColumn = previewFields.size() + 2;
        table.getColumnModel().getColumn(deleteColumn)
                .setCellRenderer(new TablePills.DeleteRenderer("Deletes the dependency in the real window"));
        table.getColumnModel().getColumn(deleteColumn).setMaxWidth(JBUI.scale(44));

        // Clicking a "Details" cell opens the style editor for that button.
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int column = table.columnAtPoint(e.getPoint());
                if (column >= 0 && table.convertColumnIndexToModel(column) == previewFields.size() + 1) {
                    new ButtonStyleDialog(table, ButtonId.SHOW_DETAILS).show();
                }
            }
        });
        return table;
    }

    private static @NotNull JBLabel sectionLabel(@NotNull String text, boolean first) {
        JBLabel label = new JBLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        label.setBorder(first ? JBUI.Borders.emptyBottom(4) : JBUI.Borders.empty(12, 0, 4, 0));
        label.setAlignmentX(0f);
        return label;
    }

    /** Stops BoxLayout from stretching a row vertically. */
    private static void capHeight(@NotNull JComponent component) {
        component.setMaximumSize(new Dimension(Integer.MAX_VALUE, component.getPreferredSize().height));
        component.setAlignmentX(0f);
    }

    // ---------------------------------------------------------------- side panel

    private @NotNull JPanel buildSidePanel() {
        UiCustomizationService service = UiCustomizationService.getInstance();
        JPanel side = new JPanel();
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setBorder(BorderFactory.createTitledBorder("Visible fields"));

        JPanel grid = new JPanel(new java.awt.GridLayout(0, 3, JBUI.scale(6), JBUI.scale(2)));
        grid.add(boldLabel("Field"));
        grid.add(boldLabel("Table"));
        grid.add(boldLabel("Details"));
        for (Field field : Field.values()) {
            grid.add(new JBLabel(field.displayName));

            JBCheckBox inTable = new JBCheckBox("", service.isShownInTable(field));
            inTable.addActionListener(e -> {
                service.setShownInTable(field, inTable.isSelected());
                service.notifyCustomizationChanged();
            });
            inTableChecks.put(field, inTable);
            grid.add(inTable);

            JBCheckBox inDetails = new JBCheckBox("", service.isShownInDetails(field));
            inDetails.addActionListener(e -> {
                service.setShownInDetails(field, inDetails.isSelected());
                service.notifyCustomizationChanged();
            });
            inDetailsChecks.put(field, inDetails);
            grid.add(inDetails);
        }
        capHeight(grid);
        side.add(grid);

        side.add(javax.swing.Box.createVerticalStrut(JBUI.scale(12)));
        JButton resetAll = new JButton("Reset All to Defaults");
        resetAll.setAlignmentX(0f);
        resetAll.addActionListener(e -> {
            service.resetToDefaults();
            service.notifyCustomizationChanged();
        });
        side.add(resetAll);
        side.add(javax.swing.Box.createVerticalGlue());
        return side;
    }

    /** Keeps the checkboxes in sync after "Reset All" (they never fire events from this). */
    private void refreshCheckboxes() {
        UiCustomizationService service = UiCustomizationService.getInstance();
        for (Field field : Field.values()) {
            inTableChecks.get(field).setSelected(service.isShownInTable(field));
            inDetailsChecks.get(field).setSelected(service.isShownInDetails(field));
        }
    }

    private static @NotNull JBLabel boldLabel(@NotNull String text) {
        JBLabel label = new JBLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        return label;
    }

    // ---------------------------------------------------------------- save / cancel

    @Override
    protected void doOKAction() {
        finish(true);
        super.doOKAction();
    }

    @Override
    public void doCancelAction() {
        finish(false);
        super.doCancelAction();
    }

    @Override
    protected void dispose() {
        finish(false);
        super.dispose();
    }

    /** Leaves edit mode exactly once; only then is the real tool window refreshed. */
    private void finish(boolean keepChanges) {
        if (finished) {
            return;
        }
        finished = true;
        UiCustomizationService service = UiCustomizationService.getInstance();
        service.removeChangeListener(changeListener);
        if (!keepChanges) {
            service.restoreFrom(snapshot);
        }
        service.setEditMode(false);
        service.notifyCustomizationChanged();
    }

    // ---------------------------------------------------------------- preview table internals

    private class PreviewTableModel extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return SAMPLE_ROWS.length;
        }

        @Override
        public int getColumnCount() {
            return previewFields.size() + 3;
        }

        @Override
        public String getColumnName(int column) {
            int fieldIndex = fieldIndexOf(column);
            return fieldIndex >= 0 && fieldIndex < previewFields.size()
                    ? previewFields.get(fieldIndex).displayName : "";
        }

        /** The previewFields index behind a model column; -1 for the Update column. */
        private int fieldIndexOf(int column) {
            int updateColumn = previewUpdateColumn();
            if (column == updateColumn) {
                return -1;
            }
            return column < updateColumn ? column : column - 1;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            String[] row = SAMPLE_ROWS[rowIndex];
            if (columnIndex == previewUpdateColumn()) {
                return "Update";
            }
            if (columnIndex == previewFields.size() + 2) {
                return "";
            }
            int fieldIndex = fieldIndexOf(columnIndex);
            if (fieldIndex >= previewFields.size()) {
                return UiCustomizationService.getInstance().getButtonText(ButtonId.SHOW_DETAILS);
            }
            return switch (previewFields.get(fieldIndex)) {
                case NAME -> row[0];
                case GROUP_ID -> row[1];
                case ARTIFACT_ID -> row[2];
                case VERSION -> row[3];
                case LATEST_VERSION -> row[4];
                case AUTO_UPDATE -> Boolean.parseBoolean(row[5]);
            };
        }
    }

    /** Mirrors the real table's per-row Update button (not customizable, shown for fidelity). */
    private static class UpdateButtonRenderer extends JButton implements TableCellRenderer {
        UpdateButtonRenderer() {
            super("Update");
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            return this;
        }
    }

    private static class DetailsButtonRenderer extends JButton implements TableCellRenderer {
        DetailsButtonRenderer() {
            UiCustomizationService.getInstance().applyTo(ButtonId.SHOW_DETAILS, this);
            setToolTipText("Click to change text, colors and size");
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            return this;
        }
    }
}

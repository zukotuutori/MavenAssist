package com.zukotuutori.mavenassist.ui;

import com.zukotuutori.mavenassist.maven.MavenBuildRunner;
import com.zukotuutori.mavenassist.maven.MavenCentralClient;
import com.zukotuutori.mavenassist.maven.PomUtils;
import com.zukotuutori.mavenassist.maven.PomWriteAction;
import com.zukotuutori.mavenassist.maven.VersionPolicy;
import com.zukotuutori.mavenassist.model.Dependency;
import com.zukotuutori.mavenassist.services.AutoUpdateRunner;
import com.zukotuutori.mavenassist.services.AutoUpdateService;
import com.zukotuutori.mavenassist.services.AutoUpdateSettings;
import com.zukotuutori.mavenassist.services.DependencyManagerService;
import com.zukotuutori.mavenassist.services.UiCustomizationService;
import com.zukotuutori.mavenassist.util.Notifier;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.table.JBTable;
import com.intellij.util.IconUtil;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.AbstractCellEditor;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shown when the chosen directory contains a pom.xml: a compact list of all
 * dependencies with a "Show Details" button per row that opens everything else.
 * Which fields appear as table columns (and which only in the details dialog),
 * is user-customizable via the "Customize..." dialog and applied through
 * {@link #applyCustomization()}. Dependencies flagged "auto update" are bumped to the
 * newest version at the moments picked in that same dialog.
 */
public class DependencyTablePanel extends JPanel {

    private static class Row {
        final Dependency dependency;
        String latest = "";

        Row(Dependency dependency) {
            this.dependency = dependency;
        }

        @NotNull String displayName() {
            return dependency.getName().isEmpty() ? dependency.getArtifactId() : dependency.getName();
        }
    }

    private final Project project;
    private final List<Row> rows = new ArrayList<>();
    /** key -> newest version, kept across reloads so the column doesn't fall back to "?". */
    private final Map<String, String> latestCache = new HashMap<>();
    /** The rows currently passing search + filter toggles; this is what the table shows. */
    private final List<Row> visibleRows = new ArrayList<>();
    private final DependencyTableModel model = new DependencyTableModel();
    private final com.intellij.ui.SearchTextField searchField = new com.intellij.ui.SearchTextField();
    private final JButton filterButton = new JButton(AllIcons.General.Filter);
    private final JButton deleteModeButton = new JButton(AllIcons.Actions.GC);
    private final JButton deleteSelectedButton = new JButton("Delete Selected", AllIcons.Actions.GC);
    private final JPanel deleteBar = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, JBUI.scale(4), 0));
    private boolean onlyAuto;

    /** True while the user is ticking dependencies for bulk deletion (extra checkbox column). */
    private boolean deleteMode;
    /** Keys ticked for bulk deletion while in delete mode. */
    private final Set<String> markedForDelete = new HashSet<>();
    private final JBLabel pomLabel = new JBLabel();
    private final JBLabel parentLabel = new JBLabel();
    private final JButton openPomButton = new JButton("Open");
    private final JButton openParentButton = new JButton("Open");
    private final JButton parentActionButton = new JButton("Add Parent Pom...");

    /** Resolved parent pom file (via relativePath), or null; controls the parent Open button. */
    private File parentPomFile;

    private final JBTable table;


    /** The dependency fields currently shown as columns (details button column excluded). */
    private List<UiCustomizationService.Field> tableFields;

    /** 1 while the delete-mode checkbox column is prepended, else 0. */
    private int colOffset() {
        return deleteMode ? 1 : 0;
    }

    /** Position of the Update column among the field columns (before the offset is applied). */
    private int updateColumnBase() {
        int version = tableFields.indexOf(UiCustomizationService.Field.VERSION);
        return version >= 0 ? version + 1 : tableFields.size();
    }

    /** Model index of the fixed Update column: right after Version, else before Details. */
    private int updateColumn() {
        return updateColumnBase() + colOffset();
    }

    /** Model index of a field's column, accounting for the inserted Update column. */
    private int columnOfField(@NotNull UiCustomizationService.Field field) {
        int index = tableFields.indexOf(field);
        if (index < 0) {
            return -1;
        }
        return (index < updateColumnBase() ? index : index + 1) + colOffset();
    }

    /** Model index of the details-button column. */
    private int detailsColumn() {
        return tableFields.size() + 1 + colOffset();
    }

    /** Model index of the delete-button column. */
    private int deleteColumn() {
        return tableFields.size() + 2 + colOffset();
    }

    /** Model index of the "⋯" actions column (always the last one). */
    private int actionsColumn() {
        return tableFields.size() + 3 + colOffset();
    }

    /** View row the mouse is currently over, or -1; only that row shows its "⋯" handle. */
    private int hoveredRow = -1;

    private File pomFile;

    public DependencyTablePanel(@NotNull Project project) {
        super(new BorderLayout(0, JBUI.scale(8)));
        this.project = project;
        setBorder(JBUI.Borders.empty(8));
        Theme.applyWindowBackground(this);

        tableFields = UiCustomizationService.getInstance().visibleTableFields();
        table = buildTable();

        add(new SectionBox("pom.xml", AllIcons.FileTypes.Xml, buildHeaderPanel()), BorderLayout.NORTH);
        JPanel tableWrap = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        tableWrap.setOpaque(false);
        tableWrap.add(buildFilterBar(), BorderLayout.NORTH);
        tableWrap.add(TablePills.scroll(table), BorderLayout.CENTER);
        tableWrap.add(buildTableButtonsRow(), BorderLayout.SOUTH);
        add(new SectionBox("Dependencies", AllIcons.Nodes.PpLib, tableWrap), BorderLayout.CENTER);

        configureTableColumns();
    }


    public void setPomFile(@NotNull File pomFile) {
        this.pomFile = pomFile;
        reload();
    }

    /** Re-reads the saved UI customization and rebuilds the table columns accordingly. */
    public void applyCustomization() {
        tableFields = UiCustomizationService.getInstance().visibleTableFields();
        model.fireTableStructureChanged();
        configureTableColumns();
        revalidate();
        repaint();
    }

    // ---------------------------------------------------------------- UI

    /** Search field with the filter-menu and delete-mode buttons right next to it. */
    private @NotNull JPanel buildFilterBar() {
        JPanel row = new JPanel(new BorderLayout(JBUI.scale(4), 0));
        row.setOpaque(false);
        row.add(searchField, BorderLayout.CENTER);

        filterButton.setToolTipText("Filter the list (only auto update)");
        filterButton.setMargin(JBUI.insets(2, 6));
        filterButton.addActionListener(e -> showFilterMenu());
        deleteModeButton.setToolTipText("Select multiple dependencies to delete from the pom");
        deleteModeButton.setMargin(JBUI.insets(2, 6));
        deleteModeButton.setIcon(TablePills.TRASH_WHITE);
        deleteModeButton.putClientProperty("JButton.backgroundColor", TablePills.DELETE);
        deleteModeButton.addActionListener(e -> setDeleteMode(!deleteMode));
        JPanel east = new JPanel(new java.awt.GridLayout(1, 2, JBUI.scale(4), 0));
        east.setOpaque(false);
        east.add(filterButton);
        east.add(deleteModeButton);
        row.add(east, BorderLayout.EAST);

        deleteSelectedButton.setIcon(TablePills.TRASH_WHITE);
        deleteSelectedButton.putClientProperty("JButton.backgroundColor", TablePills.DELETE);
        deleteSelectedButton.setForeground(Theme.ON_DESTRUCTIVE);
        deleteSelectedButton.addActionListener(e -> deleteMarked());
        JButton selectAllButton = new JButton("Select All");
        selectAllButton.setToolTipText("Tick every dependency currently shown (search/filter applied)");
        selectAllButton.addActionListener(e -> {
            for (Row visible : visibleRows) {
                markedForDelete.add(visible.dependency.getKey());
            }
            model.fireTableDataChanged();
            updateDeleteSelectedText();
        });
        JButton selectNoneButton = new JButton("Select None");
        selectNoneButton.addActionListener(e -> {
            markedForDelete.clear();
            model.fireTableDataChanged();
            updateDeleteSelectedText();
        });
        JButton cancelDeleteButton = new JButton("Cancel");
        cancelDeleteButton.addActionListener(e -> setDeleteMode(false));
        deleteBar.setOpaque(false);
        deleteBar.add(deleteSelectedButton);
        deleteBar.add(selectAllButton);
        deleteBar.add(selectNoneButton);
        deleteBar.add(cancelDeleteButton);
        deleteBar.setVisible(false);

        JPanel bar = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        bar.setOpaque(false);
        bar.add(row, BorderLayout.NORTH);
        bar.add(deleteBar, BorderLayout.CENTER);

        searchField.getTextEditor().getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                applyRowFilter();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                applyRowFilter();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                applyRowFilter();
            }
        });
        return bar;
    }

    /** Enters/leaves the multi-select delete mode (checkbox column + Delete Selected bar). */
    private void setDeleteMode(boolean on) {
        deleteMode = on;
        markedForDelete.clear();
        deleteBar.setVisible(on);
        model.fireTableStructureChanged();
        configureTableColumns();
        updateDeleteSelectedText();
        revalidate();
        repaint();
    }

    private void updateDeleteSelectedText() {
        deleteSelectedButton.setText("Delete Selected (" + markedForDelete.size() + ")");
    }

    /** Deletes every ticked dependency from the pom (one confirmation, one build). */
    private void deleteMarked() {
        File pom = pomFile;
        if (pom == null) {
            return;
        }
        List<Dependency> toDelete = new ArrayList<>();
        for (Row row : rows) {
            if (markedForDelete.contains(row.dependency.getKey())) {
                toDelete.add(row.dependency);
            }
        }
        if (toDelete.isEmpty()) {
            Notifier.warn(project, "Tick the dependencies you want to delete first.");
            return;
        }
        DependencyActions actions = actions();
        if (actions == null || !actions.confirmDelete(toDelete)) {
            return;
        }
        setDeleteMode(false);
        actions.runDelete(toDelete);
    }

    /** The filter menu that pops up under the filter button. */
    private void showFilterMenu() {
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        javax.swing.JCheckBoxMenuItem autoItem = new javax.swing.JCheckBoxMenuItem("Only auto update", onlyAuto);
        // Keep the menu open while toggling filters; it closes on Esc or a click outside.
        autoItem.putClientProperty("CheckBoxMenuItem.doNotCloseOnMouseClick", Boolean.TRUE);
        autoItem.setToolTipText("Show only dependencies with auto update turned on.");
        autoItem.addActionListener(e -> {
            onlyAuto = autoItem.isSelected();
            applyRowFilter();
        });
        menu.add(autoItem);
        menu.show(filterButton, 0, filterButton.getHeight());
    }

    /** Re-derives the visible rows from the search text and the filter toggles. */
    private void applyRowFilter() {
        String query = searchField.getText().trim().toLowerCase(java.util.Locale.ROOT);
        AutoUpdateService autoService = AutoUpdateService.getInstance(project);
        visibleRows.clear();
        for (Row row : rows) {
            if (!query.isEmpty()
                    && !row.displayName().toLowerCase(java.util.Locale.ROOT).contains(query)
                    && !row.dependency.getKey().toLowerCase(java.util.Locale.ROOT).contains(query)
                    && !row.dependency.getVersion().toLowerCase(java.util.Locale.ROOT).contains(query)) {
                continue;
            }
            if (onlyAuto
                    && (pomFile == null
                        || !autoService.isAutoUpdate(pomFile, row.dependency.getKey()))) {
                continue;
            }
            visibleRows.add(row);
        }
        model.fireTableDataChanged();
    }

    /** Fixed buttons at the bottom of the Dependencies box. */
    private @NotNull JPanel buildTableButtonsRow() {
        JPanel row = new JPanel(new java.awt.GridLayout(1, 2, JBUI.scale(4), 0));
        row.setOpaque(false);
        JButton checkUpdatesButton = new JButton("Check for Updates", AllIcons.Actions.Refresh);
        checkUpdatesButton.addActionListener(e -> fetchLatestVersions());
        row.add(checkUpdatesButton);
        JButton updateAllButton = new JButton("Update All", AllIcons.Actions.Download);
        updateAllButton.setToolTipText("Update every dependency in the pom to its newest version");
        updateAllButton.addActionListener(e -> updateAllToNewest());
        row.add(updateAllButton);
        JButton addDependenciesButton = new JButton("Add Dependencies", AllIcons.General.Add);
        addDependenciesButton.addActionListener(e -> openAddDependenciesDialog());
        row.add(addDependenciesButton);
        return row;
    }

    private @NotNull JPanel buildHeaderPanel() {
        openPomButton.setIcon(AllIcons.Actions.MenuOpen);
        openPomButton.addActionListener(e -> openInEditor(pomFile));
        openParentButton.setIcon(AllIcons.Actions.MenuOpen);
        openParentButton.addActionListener(e -> openInEditor(parentPomFile));
        openParentButton.setVisible(false);
        parentActionButton.addActionListener(e -> addParentPom());
        // Normal button colors; the hierarchy icon points up to the parent.
        parentActionButton.setIcon(com.intellij.icons.AllIcons.Hierarchy.Supertypes);
        // Lazy, so the hint color follows a theme switch instead of freezing at construction time.
        pomLabel.setForeground(JBColor.lazy(com.intellij.util.ui.UIUtil::getContextHelpForeground));

        JPanel header = new JPanel(new java.awt.GridLayout(0, 1, 0, JBUI.scale(2)));
        header.setOpaque(false);
        header.add(headerRow(pomLabel, openPomButton));
        header.add(headerRow(parentLabel, parentActionButton, openParentButton));
        return header;
    }

    private @NotNull JPanel headerRow(@NotNull JBLabel label, @NotNull JButton... buttons) {
        JPanel row = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        row.setOpaque(false);
        row.add(label, BorderLayout.CENTER);
        JPanel east = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, JBUI.scale(4), 0));
        east.setOpaque(false);
        for (JButton button : buttons) {
            button.setMargin(JBUI.insets(0, 8));
            east.add(button);
        }
        row.add(east, BorderLayout.EAST);
        return row;
    }

    private void openInEditor(@Nullable File file) {
        if (file == null) {
            return;
        }
        VirtualFile virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file);
        if (virtualFile == null) {
            Notifier.warn(project, "File not found: " + file.getAbsolutePath());
            return;
        }
        new OpenFileDescriptor(project, virtualFile).navigate(true);
    }

    private void updateHeaderLabel() {
        if (pomFile == null) {
            return;
        }
        String parentText;
        boolean hasParent;
        try {
            PomUtils.PomInfo parent = PomUtils.readParentInfo(pomFile);
            hasParent = parent != null;
            parentText = parent == null ? "none"
                    : parent.groupId() + ":" + parent.artifactId() + ":" + parent.version();
        } catch (IOException e) {
            hasParent = false;
            parentText = "?";
        }
        parentActionButton.setText(hasParent ? "Edit Parent Pom..." : "Add Parent Pom...");
        File resolvedParent;
        try {
            resolvedParent = PomUtils.readParentPomFile(pomFile);
        } catch (IOException e) {
            resolvedParent = null;
        }
        parentPomFile = resolvedParent;
        openParentButton.setVisible(resolvedParent != null);
        pomLabel.setText(pomFile.getAbsolutePath());
        parentLabel.setText("<html>Parent pom: <b>"
                + com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(parentText)
                + "</b></html>");
    }

    private @NotNull JBTable buildTable() {
        JBTable table = new ThemedTable(model);

        // Clicks: "Auto Update" cell toggles the flag, "Update" cell opens the version menu,
        // the "⋯" cell and a right-click anywhere open the full action menu.
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                int column = table.columnAtPoint(e.getPoint());
                if (row < 0 || column < 0) {
                    return;
                }
                int modelColumn = table.convertColumnIndexToModel(column);
                int autoColumn = columnOfField(UiCustomizationService.Field.AUTO_UPDATE);
                if (autoColumn >= 0 && modelColumn == autoColumn) {
                    toggleAutoUpdate(table.convertRowIndexToModel(row));
                } else if (modelColumn == updateColumn()) {
                    showUpdateMenu(table.convertRowIndexToModel(row), row, column);
                } else if (modelColumn == deleteColumn()) {
                    confirmAndDelete(table.convertRowIndexToModel(row));
                } else if (modelColumn == actionsColumn()) {
                    java.awt.Rectangle cell = table.getCellRect(row, column, true);
                    selectIfUnselected(row);
                    showRowActionsMenu(cell.x, cell.y + cell.height);
                }
            }

            // A popup trigger is mousePressed on some platforms and mouseReleased on others.
            @Override
            public void mousePressed(MouseEvent e) {
                maybePopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybePopup(e);
            }

            private void maybePopup(MouseEvent e) {
                if (!e.isPopupTrigger()) {
                    return;
                }
                int row = table.rowAtPoint(e.getPoint());
                if (row < 0) {
                    return;
                }
                selectIfUnselected(row);
                showRowActionsMenu(e.getX(), e.getY());
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                setHoveredRow(table.rowAtPoint(e.getPoint()));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                setHoveredRow(-1);
            }
        };
        table.addMouseListener(mouse);
        table.addMouseMotionListener(mouse);
        return table;
    }

    /** Assigns renderers, editors and widths; must run after every table structure change. */
    private void configureTableColumns() {
        if (deleteMode) {
            table.getColumnModel().getColumn(0).setMaxWidth(JBUI.scale(30));
        }
        int autoColumn = columnOfField(UiCustomizationService.Field.AUTO_UPDATE);
        if (autoColumn >= 0) {
            table.getColumnModel().getColumn(autoColumn).setCellRenderer(new TablePills.AutoUpdateRenderer());
            table.getColumnModel().getColumn(autoColumn).setMaxWidth(JBUI.scale(110));
        }
        UpdateButtonRenderer updateRenderer = new UpdateButtonRenderer();
        table.getColumnModel().getColumn(updateColumn()).setCellRenderer(updateRenderer);
        table.getColumnModel().getColumn(updateColumn())
                .setMaxWidth(Math.max(JBUI.scale(90), updateRenderer.getPreferredSize().width + JBUI.scale(8)));
        int detailsColumn = detailsColumn();
        DetailsButtonRenderer renderer = new DetailsButtonRenderer();
        table.getColumnModel().getColumn(detailsColumn).setCellRenderer(renderer);
        table.getColumnModel().getColumn(detailsColumn).setCellEditor(new DetailsButtonEditor());
        int width = Math.max(JBUI.scale(120), renderer.getPreferredSize().width + JBUI.scale(8));
        table.getColumnModel().getColumn(detailsColumn).setMaxWidth(width);
        table.getColumnModel().getColumn(deleteColumn())
                .setCellRenderer(new TablePills.DeleteRenderer("Delete this dependency from the pom"));
        table.getColumnModel().getColumn(deleteColumn()).setMaxWidth(JBUI.scale(44));
        table.getColumnModel().getColumn(actionsColumn()).setCellRenderer(new RowActionsRenderer());
        table.getColumnModel().getColumn(actionsColumn()).setMaxWidth(JBUI.scale(30));
    }

    /**
     * The "⋯" handle. It is drawn only on the row the mouse is over, so the column stays quiet
     * until it is needed, and a click on it opens the same menu as a right-click.
     */
    private class RowActionsRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable source, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(source, "", isSelected, false, row, column);
            setHorizontalAlignment(SwingConstants.CENTER);
            boolean hovered = row == hoveredRow;
            setIcon(hovered ? AllIcons.Actions.More : null);
            setToolTipText(hovered ? "All actions for this dependency" : null);
            return this;
        }
    }

    // ------------------------------------------------ details-dialog actions in the main window

    /** The dependency selected in the table, or null (with a hint) if there is no selection. */

    private void updateRowToNewest(@NotNull Row row) {
        DependencyActions actions = actions();
        if (actions != null) {
            actions.updateToNewest(row.dependency, row.latest);
        }
    }

    private void changeVersionOfRow(@NotNull Row row) {
        DependencyActions actions = actions();
        if (actions != null) {
            actions.askCustomVersion(row.dependency);
        }
    }

    /** Lets the user pick from every version Maven Central publishes for the row's artifact. */
    private void chooseVersionOfRow(@NotNull Row row) {
        DependencyActions actions = actions();
        if (actions != null) {
            actions.chooseFromAllVersions(row.dependency);
        }
    }

    /** Actions for the current pom, anchored on this panel and reloading the table after a write. */
    private @Nullable DependencyActions actions() {
        return pomFile == null ? null : new DependencyActions(project, pomFile, this, this::reload);
    }

    /** Repaints the "⋯" cell of the row the mouse left and of the one it entered. */
    private void setHoveredRow(int viewRow) {
        if (viewRow == hoveredRow) {
            return;
        }
        int previous = hoveredRow;
        hoveredRow = viewRow;
        repaintActionsCell(previous);
        repaintActionsCell(viewRow);
    }

    private void repaintActionsCell(int viewRow) {
        if (viewRow < 0 || viewRow >= table.getRowCount()) {
            return;
        }
        int column = table.convertColumnIndexToView(actionsColumn());
        if (column >= 0) {
            table.repaint(table.getCellRect(viewRow, column, true));
        }
    }

    /**
     * Keeps an existing multi-selection when the menu is opened from inside it, and otherwise
     * moves the selection to the clicked row, so the menu always acts on what is highlighted.
     */
    private void selectIfUnselected(int viewRow) {
        if (!table.isRowSelected(viewRow)) {
            table.setRowSelectionInterval(viewRow, viewRow);
        }
    }

    /** The selected rows, in table order; empty when nothing is selected. */
    private @NotNull List<Row> selectedRows() {
        List<Row> selected = new ArrayList<>();
        for (int viewRow : table.getSelectedRows()) {
            int modelRow = table.convertRowIndexToModel(viewRow);
            if (modelRow >= 0 && modelRow < visibleRows.size()) {
                selected.add(visibleRows.get(modelRow));
            }
        }
        return selected;
    }

    /**
     * Every action for the selected dependencies: the full set for a single row, and for a
     * multi-selection only the ones that are meaningful in bulk - a custom version or the
     * details of "all of them at once" is not.
     */
    private void showRowActionsMenu(int x, int y) {
        javax.swing.JPopupMenu menu = buildRowActionsMenu();
        if (menu != null) {
            showMenu(menu, x, y);
        }
    }

    /** @return the menu for the current selection, or null when nothing usable is selected. */
    @Nullable javax.swing.JPopupMenu buildRowActionsMenu() {
        int[] viewRows = table.getSelectedRows();
        if (viewRows.length == 0) {
            return null;
        }
        if (viewRows.length == 1) {
            int modelRow = table.convertRowIndexToModel(viewRows[0]);
            if (modelRow < 0 || modelRow >= visibleRows.size()) {
                return null;
            }
            return singleRowMenu(modelRow);
        }
        List<Row> selected = selectedRows();
        return selected.size() < 2 ? null : multiRowMenu(selected);
    }

    /** Overridden in tests, where a popup cannot be put on screen. */
    void showMenu(@NotNull javax.swing.JPopupMenu menu, int x, int y) {
        menu.show(table, x, y);
    }

    /** The table itself, for tests that drive real mouse events through it. */
    @NotNull JBTable tableForTesting() {
        return table;
    }

    private @NotNull javax.swing.JPopupMenu singleRowMenu(int modelRow) {
        Row row = visibleRows.get(modelRow);
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        menu.add(item("Details...", () -> showDetails(modelRow)));
        menu.addSeparator();
        menu.add(item("Update to newest version", () -> updateRowToNewest(row)));
        menu.add(item("Show all available versions...", () -> chooseVersionOfRow(row)));
        menu.add(item("Custom version...", () -> changeVersionOfRow(row)));
        menu.addSeparator();
        boolean on = pomFile != null
                && AutoUpdateService.getInstance(project).isAutoUpdate(pomFile, row.dependency.getKey());
        javax.swing.JCheckBoxMenuItem autoItem = new javax.swing.JCheckBoxMenuItem("Auto update", on);
        autoItem.addActionListener(e -> toggleAutoUpdate(modelRow));
        menu.add(autoItem);
        menu.addSeparator();
        menu.add(item("Delete from pom...", () -> confirmAndDelete(modelRow)));
        return menu;
    }

    private @NotNull javax.swing.JPopupMenu multiRowMenu(@NotNull List<Row> selected) {
        int count = selected.size();
        List<Dependency> dependencies = selected.stream().map(row -> row.dependency).toList();
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        javax.swing.JMenuItem header = new javax.swing.JMenuItem(count + " dependencies selected");
        header.setEnabled(false);
        menu.add(header);
        menu.addSeparator();
        menu.add(item("Update " + count + " dependencies to the newest version",
                () -> updateRowsToNewest(List.copyOf(selected), "selected dependencies")));
        menu.addSeparator();
        menu.add(item("Turn auto update on for " + count + " dependencies",
                () -> setAutoUpdateForAll(dependencies, true)));
        menu.add(item("Turn auto update off for " + count + " dependencies",
                () -> setAutoUpdateForAll(dependencies, false)));
        menu.addSeparator();
        menu.add(item("Delete " + count + " dependencies from pom...", () -> {
            DependencyActions actions = actions();
            if (actions != null && actions.confirmDelete(dependencies)) {
                actions.runDelete(dependencies);
            }
        }));
        return menu;
    }

    private static @NotNull javax.swing.JMenuItem item(@NotNull String text, @NotNull Runnable action) {
        javax.swing.JMenuItem menuItem = new javax.swing.JMenuItem(text);
        menuItem.addActionListener(e -> action.run());
        return menuItem;
    }

    /** Flips auto update for a whole selection, asking once instead of per dependency. */
    private void setAutoUpdateForAll(@NotNull List<Dependency> dependencies, boolean enable) {
        DependencyActions actions = actions();
        if (actions != null && actions.setAutoUpdateForAll(dependencies, enable)) {
            // Re-filter: with "Only auto update" active, rows turned off disappear immediately.
            applyRowFilter();
        }
    }

    /** The "newest / all available / custom version" menu of a row's Update button. */
    private void showUpdateMenu(int modelRow, int viewRow, int viewColumn) {
        if (modelRow < 0 || modelRow >= visibleRows.size()) {
            return;
        }
        Row row = visibleRows.get(modelRow);
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        javax.swing.JMenuItem newest = new javax.swing.JMenuItem("Newest version");
        newest.addActionListener(e -> updateRowToNewest(row));
        menu.add(newest);
        javax.swing.JMenuItem all = new javax.swing.JMenuItem("Show all available versions...");
        all.addActionListener(e -> chooseVersionOfRow(row));
        menu.add(all);
        javax.swing.JMenuItem custom = new javax.swing.JMenuItem("Custom version...");
        custom.addActionListener(e -> changeVersionOfRow(row));
        menu.add(custom);
        java.awt.Rectangle cell = table.getCellRect(viewRow, viewColumn, true);
        menu.show(table, cell.x, cell.y + cell.height);
    }

    // ---------------------------------------------------------------- actions

    public void reload() {
        if (pomFile == null) {
            return;
        }
        try {
            // Resolve display names ("JSON", "Guava", ...) from the dependency manager catalog.
            Map<String, String> namesByKey = new HashMap<>();
            for (Dependency d : DependencyManagerService.getInstance().getAllDependencies()) {
                if (!d.getName().isEmpty()) {
                    namesByKey.put(d.getKey(), d.getName());
                }
            }
            List<Dependency> dependencies = PomUtils.readDependencies(pomFile);
            rows.clear();
            for (Dependency d : dependencies) {
                String name = namesByKey.get(d.getKey());
                Row row = new Row(name == null ? d
                        : new Dependency(d.getGroupId(), d.getArtifactId(), d.getVersion(), name));
                String cachedLatest = latestCache.get(d.getKey());
                if (cachedLatest != null) {
                    row.latest = cachedLatest;
                }
                rows.add(row);
            }
            applyRowFilter();
        } catch (IOException e) {
            Notifier.error(project, "Could not read " + pomFile.getName() + ": " + e.getMessage());
        }
        updateHeaderLabel();
    }

    private void showDetails(int rowIndex) {
        if (rowIndex < 0 || rowIndex >= visibleRows.size() || pomFile == null) {
            return;
        }
        Row row = visibleRows.get(rowIndex);
        new DependencyDetailsDialog(project, row.dependency, row.latest, pomFile,
                this::applyVersion, latest -> {
                    row.latest = latest;
                    latestCache.put(row.dependency.getKey(), latest);
                    model.fireTableRowsUpdated(rowIndex, rowIndex);
                }).show();
        // The dialog may have toggled the auto-update flag.
        model.fireTableRowsUpdated(rowIndex, rowIndex);
    }

    /** Writes the given version for the dependency into the pom, refreshes and builds. */
    private void applyVersion(@NotNull Dependency dep, @NotNull String newVersion) {
        DependencyActions actions = actions();
        if (actions != null) {
            actions.applyVersion(dep, newVersion);
        }
    }

    /** The red trash button: asks for confirmation, then removes the dependency from the pom. */
    private void confirmAndDelete(int rowIndex) {
        DependencyActions actions = actions();
        if (actions != null && rowIndex >= 0 && rowIndex < visibleRows.size()) {
            actions.confirmAndDelete(visibleRows.get(rowIndex).dependency);
        }
    }

    private void toggleAutoUpdate(int rowIndex) {
        DependencyActions actions = actions();
        if (actions == null || rowIndex < 0 || rowIndex >= visibleRows.size()) {
            return;
        }
        if (actions.toggleAutoUpdate(visibleRows.get(rowIndex).dependency)) {
            // Re-filter: with "Only auto update" active, a turned-off row disappears immediately.
            applyRowFilter();
        }
    }

    /**
     * Bumps every dependency flagged "auto update" to the newest version, if the given
     * moment is one the user picked in "Customize...". The pass itself lives in
     * {@link AutoUpdateRunner} so the triggers that fire with this panel closed behave
     * identically.
     */
    public void runAutoUpdate(@NotNull AutoUpdateSettings.Trigger trigger) {
        File pom = pomFile;
        if (pom == null || !pom.isFile()) {
            return;
        }
        AutoUpdateRunner.runIfEnabled(project, trigger, pom, this::reload);
    }

    /** Fetches the newest version of every dependency and reports a summary notification. */
    private void fetchLatestVersions() {
        File pom = pomFile;
        List<Row> snapshot = new ArrayList<>(rows);
        new Task.Backgroundable(project, "Maven Assist: Checking for updates", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                List<String> outdated = new ArrayList<>();
                List<String> unavailable = new ArrayList<>();
                for (Row row : snapshot) {
                    indicator.checkCanceled();
                    Dependency dep = row.dependency;
                    indicator.setText("Checking " + dep.getKey() + "...");
                    String latest = MavenCentralClient.getLatestVersion(dep.getGroupId(), dep.getArtifactId());
                    if (latest != null) {
                        ApplicationManager.getApplication().invokeLater(() -> {
                            latestCache.put(dep.getKey(), latest);
                            if (pom.equals(pomFile)) {
                                row.latest = latest;
                                applyRowFilter();
                            }
                        });
                        if (VersionPolicy.isUpgrade(dep.getVersion(), latest)) {
                            outdated.add(row.displayName() + ": " + dep.getVersion() + " → " + latest);
                        }
                    } else {
                        unavailable.add(dep.getKey());
                        ApplicationManager.getApplication().invokeLater(() -> {
                            if (pom.equals(pomFile)) {
                                row.latest = "?";
                                applyRowFilter();
                            }
                        });
                    }
                }
                if (outdated.isEmpty() && unavailable.isEmpty()) {
                    Notifier.info(project, "All dependencies are up to date. ✔");
                } else if (!outdated.isEmpty()) {
                    Notifier.info(project, outdated.size() + " update(s) available:\n" + String.join("\n", outdated)
                            + "\nOpen \"Details\" on a dependency to update it.");
                }
                if (!unavailable.isEmpty()) {
                    Notifier.warn(project, "Could not determine the newest version for "
                            + unavailable.size() + " dependencies:\n" + String.join("\n", unavailable)
                            + "\nThese dependencies were not reported as up to date.");
                }
            }
        }.queue();
    }

    /** Confirms the scope, updates those dependencies, then builds once. */
    private void updateAllToNewest() {
        File pom = pomFile;
        if (pom == null) {
            return;
        }
        int answer = com.intellij.openapi.ui.Messages.showYesNoCancelDialog(project,
                "Are you sure you want to update all dependencies?",
                "Update All Dependencies",
                "Yes", "Only Selected...", "No",
                com.intellij.openapi.ui.Messages.getQuestionIcon());
        if (answer == com.intellij.openapi.ui.Messages.CANCEL) {
            return;
        }

        List<Row> snapshot;
        boolean allDependencies = answer == com.intellij.openapi.ui.Messages.YES;
        if (allDependencies) {
            snapshot = new ArrayList<>(rows);
        } else {
            List<Dependency> dependencies = rows.stream().map(row -> row.dependency).toList();
            SelectDependenciesToUpdateDialog dialog =
                    new SelectDependenciesToUpdateDialog(project, dependencies);
            if (!dialog.showAndGet()) {
                return;
            }
            Set<String> selectedKeys = new HashSet<>();
            for (Dependency dependency : dialog.getSelectedDependencies()) {
                selectedKeys.add(dependency.getKey());
            }
            snapshot = rows.stream()
                    .filter(row -> selectedKeys.contains(row.dependency.getKey()))
                    .toList();
        }

        updateRowsToNewest(snapshot, allDependencies ? "dependencies" : "selected dependencies");
    }

    /**
     * Bumps the given rows to their newest stable version in one background pass: one progress
     * bar, one answer to the parent-property question, one summary notification and one build.
     *
     * @param scope how the selection is named in the progress bar and the summary
     */
    private void updateRowsToNewest(@NotNull List<Row> snapshot, @NotNull String scope) {
        DependencyActions actions = actions();
        if (actions == null) {
            return;
        }
        List<Dependency> dependencies = new ArrayList<>();
        Map<String, String> cachedLatest = new HashMap<>();
        for (Row row : snapshot) {
            dependencies.add(row.dependency);
            if (!row.latest.isEmpty()) {
                cachedLatest.put(row.dependency.getKey(), row.latest);
            }
        }
        actions.updateAllToNewest(dependencies, cachedLatest, scope);
    }

    /** Lets the user pick a parent pom.xml via path and sets/replaces the &lt;parent&gt; block. */
    private void addParentPom() {
        if (pomFile == null) {
            return;
        }
        FileChooserDescriptor descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("xml")
                .withTitle("Choose Parent pom.xml");
        VirtualFile chosen = FileChooser.chooseFile(descriptor, project, null);
        if (chosen == null) {
            return;
        }
        File parentFile = new File(chosen.getPath());
        if (parentFile.getAbsolutePath().equals(pomFile.getAbsolutePath())) {
            Notifier.warn(project, "A pom cannot be its own parent.");
            return;
        }
        try {
            // Re-selecting the pom's current parent is a no-op: no rewrite, no dialog, no build.
            if (PomUtils.parentAlreadySet(pomFile, parentFile)) {
                return;
            }
            if (!ensureParentPackaging(parentFile)) {
                return;
            }
            PomUtils.PomInfo parentInfo = PomUtils.readPomInfo(parentFile);
            PomWriteAction.run(project, pomFile, "Set parent POM",
                    () -> PomUtils.setParentPom(pomFile, parentInfo, parentFile));
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(pomFile);
            Notifier.info(project, "Parent pom set to " + parentInfo.groupId() + ":"
                    + parentInfo.artifactId() + ":" + parentInfo.version() + ". Building...");
            reload();
            MavenBuildRunner.build(project, pomFile.getParentFile(), null);
        } catch (IOException e) {
            Notifier.error(project, "Could not set parent pom: " + e.getMessage());
        }
    }

    /**
     * Maven refuses parent poms whose packaging is not "pom". Offers to fix the chosen
     * parent; returns false when the user cancels instead.
     */
    private boolean ensureParentPackaging(@NotNull File parentFile) throws IOException {
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
            Notifier.warn(project, "Parent pom was not set. Its packaging must be \"pom\".");
            return false;
        }
        PomWriteAction.run(project, parentFile, "Set parent POM packaging",
                () -> PomUtils.ensurePomPackaging(parentFile));
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(parentFile);
        return true;
    }

    private void openAddDependenciesDialog() {
        if (pomFile == null) {
            return;
        }
        Set<String> existingKeys = new HashSet<>();
        for (Row row : rows) {
            existingKeys.add(row.dependency.getKey());
        }
        AddDependenciesDialog dialog = new AddDependenciesDialog(project, existingKeys);
        if (!dialog.showAndGet()) {
            return;
        }
        List<Dependency> selected = dialog.getSelectedDependencies();
        Set<String> fixedVersionKeys = dialog.getFixedVersionKeys();
        Set<String> autoUpdateKeys = dialog.getAutoUpdateKeys();
        if (selected.isEmpty()) {
            return;
        }
        File pom = pomFile;

        new Task.Backgroundable(project, "Maven Assist: Adding dependencies", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                List<Dependency> resolved = new ArrayList<>();
                List<String> fallbackVersions = new ArrayList<>();
                for (Dependency dep : selected) {
                    indicator.checkCanceled();
                    String version = dep.getVersion();
                    if (!fixedVersionKeys.contains(dep.getKey())) {
                        indicator.setText("Fetching newest version of " + dep.getKey() + "...");
                        String latest = MavenCentralClient.getLatestVersion(dep.getGroupId(), dep.getArtifactId());
                        if (latest == null) {
                            fallbackVersions.add(dep.getKey() + " → "
                                    + (version.isBlank() ? "(no catalog version)" : version));
                        }
                        version = VersionPolicy.newestOrFallback(version, latest);
                    }
                    resolved.add(dep.withVersion(version));
                }
                // Never write the same coordinates twice: re-check against the pom on disk.
                List<Dependency> toAdd = new ArrayList<>();
                List<String> duplicates = new ArrayList<>();
                try {
                    Set<String> present = new HashSet<>();
                    for (Dependency existing : PomUtils.readDependencies(pom)) {
                        present.add(existing.getKey());
                    }
                    for (Dependency dep : resolved) {
                        if (present.add(dep.getKey())) {
                            toAdd.add(dep);
                        } else {
                            duplicates.add(dep.getKey());
                        }
                    }
                } catch (IOException e) {
                    Notifier.error(project, "Could not read " + pom.getName() + ": " + e.getMessage());
                    return;
                }
                if (!duplicates.isEmpty()) {
                    Notifier.error(project, "Already in the pom. It was not added again: "
                            + String.join(", ", duplicates));
                }
                if (toAdd.isEmpty()) {
                    return;
                }
                try {
                    PomWriteAction.run(project, pom, "Add Maven dependencies",
                            () -> PomUtils.addDependencies(pom, toAdd));
                } catch (IOException e) {
                    Notifier.error(project, "Failed to add dependencies: " + e.getMessage());
                    return;
                }
                int autoUpdateCount = 0;
                AutoUpdateService service = AutoUpdateService.getInstance(project);
                for (Dependency dep : toAdd) {
                    if (autoUpdateKeys.contains(dep.getKey())) {
                        service.setAutoUpdate(pom, dep.getKey(), true);
                        autoUpdateCount++;
                    }
                }
                Notifier.info(project, "Added " + toAdd.size() + " dependencies to pom.xml"
                        + (autoUpdateCount > 0 ? " (" + autoUpdateCount + " with auto update)" : "")
                        + ". Building...");
                if (!fallbackVersions.isEmpty()) {
                    Notifier.warn(project, "Could not determine the newest version for:\n"
                            + String.join("\n", fallbackVersions)
                            + "\nThe listed catalog versions were used instead.");
                }
                ApplicationManager.getApplication().invokeLater(() -> {
                    LocalFileSystem.getInstance().refreshAndFindFileByIoFile(pom);
                    reload();
                });
                MavenBuildRunner.build(project, pom.getParentFile(), null);
            }
        }.queue();
    }

    // ---------------------------------------------------------------- table model

    /** Columns = the user's visible fields (in canonical order) plus Update and Details buttons. */
    private class DependencyTableModel extends AbstractTableModel {

        @Override
        public int getRowCount() {
            return visibleRows.size();
        }

        @Override
        public int getColumnCount() {
            return tableFields.size() + 4 + colOffset();
        }

        @Override
        public String getColumnName(int column) {
            if ((deleteMode && column == 0) || column == updateColumn() || column >= detailsColumn()) {
                return "";
            }
            int fieldIndex = fieldIndexOf(column);
            return fieldIndex >= 0 && fieldIndex < tableFields.size()
                    ? tableFields.get(fieldIndex).displayName : "";
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return deleteMode && columnIndex == 0 ? Boolean.class : Object.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return (deleteMode && columnIndex == 0) || columnIndex == detailsColumn();
        }

        /** The tableFields index behind a model column; -1 for the Update column. */
        private int fieldIndexOf(int column) {
            int c = column - colOffset();
            int updateBase = updateColumnBase();
            if (c == updateBase) {
                return -1;
            }
            return c < updateBase ? c : c - 1;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Row row = visibleRows.get(rowIndex);
            if (deleteMode && columnIndex == 0) {
                return markedForDelete.contains(row.dependency.getKey());
            }
            if (columnIndex == updateColumn()) {
                return "Update";
            }
            if (columnIndex == deleteColumn() || columnIndex == actionsColumn()) {
                return "";
            }
            int fieldIndex = fieldIndexOf(columnIndex);
            if (fieldIndex >= tableFields.size()) {
                return "Details";
            }
            return switch (tableFields.get(fieldIndex)) {
                case NAME -> row.displayName();
                case GROUP_ID -> row.dependency.getGroupId();
                case ARTIFACT_ID -> row.dependency.getArtifactId();
                case VERSION -> row.dependency.getVersion().isEmpty() ? "(managed)" : row.dependency.getVersion();
                case LATEST_VERSION -> row.latest.isEmpty() ? "?" : row.latest;
                case AUTO_UPDATE -> pomFile != null && AutoUpdateService.getInstance(project)
                        .isAutoUpdate(pomFile, row.dependency.getKey());
            };
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            if (deleteMode && columnIndex == 0 && rowIndex < visibleRows.size()) {
                String key = visibleRows.get(rowIndex).dependency.getKey();
                if (Boolean.TRUE.equals(aValue)) {
                    markedForDelete.add(key);
                } else {
                    markedForDelete.remove(key);
                }
                updateDeleteSelectedText();
            }
        }
    }

    /** The per-row Update button; a click opens the "newest / custom version" menu. */
    private static class UpdateButtonRenderer extends JButton implements TableCellRenderer {
        UpdateButtonRenderer() {
            super("Update");
            setToolTipText("Update this dependency: newest version, one from the full list, or a custom one");
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            return this;
        }
    }

    private static class DetailsButtonRenderer extends JButton implements TableCellRenderer {
        DetailsButtonRenderer() {
            super("Details");
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            return this;
        }
    }

    private class DetailsButtonEditor extends AbstractCellEditor implements TableCellEditor {
        private final JButton button = new JButton();
        private int editedRow = -1;

        DetailsButtonEditor() {
            button.setText("Details");
            button.addActionListener(e -> {
                int row = editedRow;
                fireEditingStopped();
                showDetails(row);
            });
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected,
                                                     int row, int column) {
            editedRow = row;
            // Cell editors sit outside the component tree, so a theme switch never reaches
            // them; re-installing the UI here keeps the button in the IDE's current theme.
            button.updateUI();
            return button;
        }

        @Override
        public @Nullable Object getCellEditorValue() {
            return null;
        }
    }
}

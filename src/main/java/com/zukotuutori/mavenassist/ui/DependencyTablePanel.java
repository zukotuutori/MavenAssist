package com.zukotuutori.mavenassist.ui;

import com.zukotuutori.mavenassist.maven.MavenBuildRunner;
import com.zukotuutori.mavenassist.maven.MavenCentralClient;
import com.zukotuutori.mavenassist.maven.PomUtils;
import com.zukotuutori.mavenassist.model.Dependency;
import com.zukotuutori.mavenassist.services.AutoUpdateService;
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
import com.intellij.ui.components.JBScrollPane;
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
import java.awt.Color;
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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shown when the chosen directory contains a pom.xml: a compact list of all
 * dependencies with a "Show Details" button per row that opens everything else.
 * Which fields appear as table columns (and which only in the details dialog),
 * plus the action buttons' text, colors, order and size, are all user-customizable
 * via the "Customize UI" dialog and applied through {@link #applyCustomization()}.
 * Dependencies flagged "auto update" are bumped to the newest version automatically
 * whenever the project is saved.
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
    private boolean onlyNewest;
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
    private final AtomicBoolean autoUpdateRunning = new AtomicBoolean(false);

    /** Resolved parent pom file (via relativePath), or null — controls the parent Open button. */
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

    /** Model index of the delete-button column (always the last one). */
    private int deleteColumn() {
        return tableFields.size() + 2 + colOffset();
    }

    private File pomFile;

    public DependencyTablePanel(@NotNull Project project) {
        super(new BorderLayout(0, JBUI.scale(8)));
        this.project = project;
        setBorder(JBUI.Borders.empty(8));

        tableFields = UiCustomizationService.getInstance().visibleTableFields();
        table = buildTable();

        add(new SectionBox("pom.xml", AllIcons.FileTypes.Xml, buildHeaderPanel()), BorderLayout.NORTH);
        JPanel tableWrap = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        tableWrap.add(buildFilterBar(), BorderLayout.NORTH);
        JBScrollPane tableScroll = new JBScrollPane(table);
        tableScroll.setBorder(JBUI.Borders.empty());
        tableWrap.add(tableScroll, BorderLayout.CENTER);
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

        filterButton.setToolTipText("Filter the list (only newest version / only auto update)");
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
        deleteSelectedButton.setForeground(Color.WHITE);
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
        StringBuilder names = new StringBuilder();
        for (Dependency dep : toDelete) {
            names.append('\n').append(dep.getKey());
        }
        int answer = com.intellij.openapi.ui.Messages.showYesNoDialog(project,
                "Delete " + toDelete.size() + " dependencies from pom.xml?" + names
                        + "\n\nThe whole <dependency> entries will be removed.",
                "Delete Dependencies", "Delete", "Cancel",
                com.intellij.openapi.ui.Messages.getWarningIcon());
        if (answer != com.intellij.openapi.ui.Messages.YES) {
            return;
        }
        setDeleteMode(false);
        new Task.Backgroundable(project, "Maven Assist: Deleting dependencies", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                int deleted = 0;
                List<String> failures = new ArrayList<>();
                AutoUpdateService autoService = AutoUpdateService.getInstance();
                for (Dependency dep : toDelete) {
                    indicator.checkCanceled();
                    indicator.setText("Deleting " + dep.getKey() + "...");
                    try {
                        PomUtils.removeDependency(pom, dep.getGroupId(), dep.getArtifactId());
                        autoService.setAutoUpdate(pom.getAbsolutePath(), dep.getKey(), false);
                        deleted++;
                    } catch (IOException e) {
                        failures.add(dep.getKey() + ": " + e.getMessage());
                    }
                }
                if (!failures.isEmpty()) {
                    Notifier.error(project, "Could not delete:\n" + String.join("\n", failures));
                }
                if (deleted == 0) {
                    return;
                }
                Notifier.info(project, "Deleted " + deleted + " dependencies from pom.xml. Building...");
                ApplicationManager.getApplication().invokeLater(() -> {
                    LocalFileSystem.getInstance().refreshAndFindFileByIoFile(pom);
                    reload();
                });
                MavenBuildRunner.build(project, pom.getParentFile(), null);
            }
        }.queue();
    }

    /** The filter menu that pops up under the filter button. */
    private void showFilterMenu() {
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        javax.swing.JCheckBoxMenuItem newestItem = new javax.swing.JCheckBoxMenuItem("Only newest version", onlyNewest);
        // Keep the menu open while toggling filters; it closes on Esc or a click outside.
        newestItem.putClientProperty("CheckBoxMenuItem.doNotCloseOnMouseClick", Boolean.TRUE);
        newestItem.setToolTipText("Show only dependencies that are already on the newest version "
                + "(missing newest versions are fetched automatically).");
        newestItem.addActionListener(e -> {
            onlyNewest = newestItem.isSelected();
            applyRowFilter();
            if (onlyNewest) {
                fetchMissingLatestVersions();
            }
        });
        menu.add(newestItem);
        javax.swing.JCheckBoxMenuItem autoItem = new javax.swing.JCheckBoxMenuItem("Only auto update", onlyAuto);
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
        AutoUpdateService autoService = AutoUpdateService.getInstance();
        visibleRows.clear();
        for (Row row : rows) {
            if (!query.isEmpty()
                    && !row.displayName().toLowerCase(java.util.Locale.ROOT).contains(query)
                    && !row.dependency.getKey().toLowerCase(java.util.Locale.ROOT).contains(query)
                    && !row.dependency.getVersion().toLowerCase(java.util.Locale.ROOT).contains(query)) {
                continue;
            }
            if (onlyNewest
                    && (row.latest.isEmpty() || "?".equals(row.latest)
                        || !row.latest.equals(row.dependency.getVersion()))) {
                continue;
            }
            if (onlyAuto
                    && (pomFile == null
                        || !autoService.isAutoUpdate(pomFile.getAbsolutePath(), row.dependency.getKey()))) {
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
        pomLabel.setForeground(com.intellij.util.ui.UIUtil.getContextHelpForeground());

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
        parentLabel.setText("<html>Parent pom: <b>" + parentText + "</b></html>");
    }

    private @NotNull JBTable buildTable() {
        JBTable table = new JBTable(model);
        TablePills.modernize(table);

        // Clicks: "Auto Update" cell toggles the flag, "Update" cell opens the version menu.
        table.addMouseListener(new MouseAdapter() {
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
                }
            }
        });
        return table;
    }

    /** Assigns renderers, editors and widths — must run after every table structure change. */
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
    }

    // ------------------------------------------------ details-dialog actions in the main window

    /** The dependency selected in the table, or null (with a hint) if there is no selection. */

    private void updateRowToNewest(@NotNull Row row) {
        Dependency dep = row.dependency;
        String cached = row.latest;
        new Task.Backgroundable(project, "Maven Assist: Fetching newest version of " + dep.getKey(), true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                String latest = cached.isEmpty() || "?".equals(cached)
                        ? MavenCentralClient.getLatestVersion(dep.getGroupId(), dep.getArtifactId())
                        : cached;
                if (latest == null) {
                    Notifier.warn(project, "Could not determine the newest version of " + dep.getKey() + ".");
                    return;
                }
                applyVersion(dep, latest);
            }
        }.queue();
    }

    private void changeVersionOfRow(@NotNull Row row) {
        String input = VersionInput.ask(project, this,
                row.dependency.getGroupId(), row.dependency.getArtifactId(),
                "Custom version for " + row.dependency.getKey() + ":", row.dependency.getVersion());
        if (input == null || input.isEmpty()) {
            return;
        }
        applyVersion(row.dependency, input);
    }

    /** The "newest / custom version" menu of a row's Update button. */
    private void showUpdateMenu(int modelRow, int viewRow, int viewColumn) {
        if (modelRow < 0 || modelRow >= visibleRows.size()) {
            return;
        }
        Row row = visibleRows.get(modelRow);
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        javax.swing.JMenuItem newest = new javax.swing.JMenuItem("Newest version");
        newest.addActionListener(e -> updateRowToNewest(row));
        menu.add(newest);
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
            fetchMissingLatestVersions();
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
        new DependencyDetailsDialog(project, row.dependency, row.latest, pomFile.getAbsolutePath(),
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
        File pom = pomFile;
        if (pom == null) {
            return;
        }
        new Task.Backgroundable(project, "Maven Assist: Updating " + dep.getKey(), true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                if (newVersion.equals(dep.getVersion())) {
                    Notifier.info(project, dep.getKey() + " is already at version " + newVersion + ".");
                    return;
                }
                try {
                    PomUtils.setDependencyVersion(pom, dep.getGroupId(), dep.getArtifactId(), newVersion);
                } catch (IOException e) {
                    Notifier.error(project, "Failed to update " + dep.getKey() + ": " + e.getMessage());
                    return;
                }
                Notifier.info(project, "Updated " + dep.getKey() + " from "
                        + (dep.getVersion().isEmpty() ? "(managed)" : dep.getVersion())
                        + " to " + newVersion + ". Building...");
                ApplicationManager.getApplication().invokeLater(() -> {
                    LocalFileSystem.getInstance().refreshAndFindFileByIoFile(pom);
                    reload();
                });
                MavenBuildRunner.build(project, pom.getParentFile(), null);
            }
        }.queue();
    }

    /** The red trash button: asks for confirmation, then removes the dependency from the pom. */
    private void confirmAndDelete(int rowIndex) {
        if (rowIndex < 0 || rowIndex >= visibleRows.size() || pomFile == null) {
            return;
        }
        Dependency dep = visibleRows.get(rowIndex).dependency;
        int answer = com.intellij.openapi.ui.Messages.showYesNoDialog(project,
                "Delete " + dep.getKey() + " from pom.xml?\nThe whole <dependency> entry will be removed.",
                "Delete Dependency", "Delete", "Cancel",
                com.intellij.openapi.ui.Messages.getWarningIcon());
        if (answer != com.intellij.openapi.ui.Messages.YES) {
            return;
        }
        File pom = pomFile;
        new Task.Backgroundable(project, "Maven Assist: Deleting " + dep.getKey(), true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    PomUtils.removeDependency(pom, dep.getGroupId(), dep.getArtifactId());
                } catch (IOException e) {
                    Notifier.error(project, "Failed to delete " + dep.getKey() + ": " + e.getMessage());
                    return;
                }
                AutoUpdateService.getInstance().setAutoUpdate(pom.getAbsolutePath(), dep.getKey(), false);
                Notifier.info(project, "Deleted " + dep.getKey() + " from pom.xml. Building...");
                ApplicationManager.getApplication().invokeLater(() -> {
                    LocalFileSystem.getInstance().refreshAndFindFileByIoFile(pom);
                    reload();
                });
                MavenBuildRunner.build(project, pom.getParentFile(), null);
            }
        }.queue();
    }

    private void toggleAutoUpdate(int rowIndex) {
        if (rowIndex < 0 || rowIndex >= visibleRows.size() || pomFile == null) {
            return;
        }
        Dependency dep = visibleRows.get(rowIndex).dependency;
        AutoUpdateService service = AutoUpdateService.getInstance();
        String pomPath = pomFile.getAbsolutePath();
        service.setAutoUpdate(pomPath, dep.getKey(), !service.isAutoUpdate(pomPath, dep.getKey()));
        // Re-filter: with "Only auto update" active, a turned-off row disappears immediately.
        applyRowFilter();
    }

    /**
     * Bumps every dependency flagged "auto update" to the newest version.
     * Called when the project is saved (and after external pom changes).
     */
    public void runAutoUpdate() {
        File pom = pomFile;
        if (pom == null || !pom.isFile() || !autoUpdateRunning.compareAndSet(false, true)) {
            return;
        }
        AutoUpdateService service = AutoUpdateService.getInstance();
        String pomPath = pom.getAbsolutePath();
        List<Dependency> flagged = rows.stream()
                .map(r -> r.dependency)
                .filter(d -> service.isAutoUpdate(pomPath, d.getKey()))
                .toList();
        if (flagged.isEmpty()) {
            autoUpdateRunning.set(false);
            return;
        }

        new Task.Backgroundable(project, "Maven Assist: Auto-updating dependencies", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                List<String> updated = new ArrayList<>();
                for (Dependency dep : flagged) {
                    indicator.checkCanceled();
                    indicator.setText("Checking " + dep.getKey() + "...");
                    String latest = MavenCentralClient.getLatestVersion(dep.getGroupId(), dep.getArtifactId());
                    if (latest == null || latest.equals(dep.getVersion())) {
                        continue;
                    }
                    try {
                        PomUtils.setDependencyVersion(pom, dep.getGroupId(), dep.getArtifactId(), latest);
                        updated.add(dep.getKey() + " → " + latest);
                    } catch (IOException e) {
                        Notifier.error(project, "Auto-update of " + dep.getKey() + " failed: " + e.getMessage());
                    }
                }
                if (!updated.isEmpty()) {
                    Notifier.info(project, "Auto-updated " + updated.size() + " dependencies:\n"
                            + String.join("\n", updated) + "\nBuilding...");
                    ApplicationManager.getApplication().invokeLater(() -> {
                        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(pom);
                        reload();
                    });
                    MavenBuildRunner.build(project, pom.getParentFile(), null);
                }
            }

            @Override
            public void onFinished() {
                autoUpdateRunning.set(false);
            }
        }.queue();
    }

    /**
     * Silently fills in missing newest versions so the "Only newest version" filter has
     * data to compare against; matching rows appear in the filtered list as results arrive.
     */
    private void fetchMissingLatestVersions() {
        File pom = pomFile;
        if (pom == null) {
            return;
        }
        List<Row> missing = new ArrayList<>();
        for (Row row : rows) {
            if (row.latest.isEmpty() || "?".equals(row.latest)) {
                missing.add(row);
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        new Task.Backgroundable(project, "Maven Assist: Fetching newest versions", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                for (Row row : missing) {
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
                    }
                }
            }
        }.queue();
    }

    /** Fetches the newest version of every dependency and reports a summary notification. */
    private void fetchLatestVersions() {
        File pom = pomFile;
        List<Row> snapshot = new ArrayList<>(rows);
        new Task.Backgroundable(project, "Maven Assist: Checking for updates", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                List<String> outdated = new ArrayList<>();
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
                        if (!latest.equals(dep.getVersion())) {
                            outdated.add(row.displayName() + ": " + dep.getVersion() + " → " + latest);
                        }
                    }
                }
                if (outdated.isEmpty()) {
                    Notifier.info(project, "All dependencies are up to date. ✔");
                } else {
                    Notifier.info(project, outdated.size() + " update(s) available:\n" + String.join("\n", outdated)
                            + "\nOpen \"Details\" on a dependency to update it.");
                }
            }
        }.queue();
    }

    /** Bumps every dependency in the pom to its newest version, then builds once. */
    private void updateAllToNewest() {
        File pom = pomFile;
        if (pom == null) {
            return;
        }
        List<Row> snapshot = new ArrayList<>(rows);
        new Task.Backgroundable(project, "Maven Assist: Updating all dependencies", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                int updated = 0;
                List<String> failures = new ArrayList<>();
                for (Row row : snapshot) {
                    indicator.checkCanceled();
                    Dependency dep = row.dependency;
                    indicator.setText("Checking " + dep.getKey() + "...");
                    String latest = row.latest.isEmpty() || "?".equals(row.latest)
                            ? MavenCentralClient.getLatestVersion(dep.getGroupId(), dep.getArtifactId())
                            : row.latest;
                    if (latest == null || latest.equals(dep.getVersion())) {
                        continue;
                    }
                    try {
                        indicator.setText("Updating " + dep.getKey() + " to " + latest + "...");
                        PomUtils.setDependencyVersion(pom, dep.getGroupId(), dep.getArtifactId(), latest);
                        updated++;
                    } catch (IOException e) {
                        failures.add(dep.getKey() + ": " + e.getMessage());
                    }
                }
                if (!failures.isEmpty()) {
                    Notifier.error(project, "Could not update:\n" + String.join("\n", failures));
                }
                if (updated == 0) {
                    if (failures.isEmpty()) {
                        Notifier.info(project, "All dependencies are already up to date. ✔");
                    }
                    return;
                }
                Notifier.info(project, "Updated " + updated + " dependencies to the newest version. Building...");
                ApplicationManager.getApplication().invokeLater(() -> {
                    LocalFileSystem.getInstance().refreshAndFindFileByIoFile(pom);
                    reload();
                });
                MavenBuildRunner.build(project, pom.getParentFile(), null);
            }
        }.queue();
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
            PomUtils.setParentPom(pomFile, parentInfo, parentFile);
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
            Notifier.warn(project, "Parent pom not set — its packaging must be \"pom\".");
            return false;
        }
        PomUtils.ensurePomPackaging(parentFile);
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
                for (Dependency dep : selected) {
                    indicator.checkCanceled();
                    String version = dep.getVersion();
                    if (!fixedVersionKeys.contains(dep.getKey())) {
                        indicator.setText("Fetching newest version of " + dep.getKey() + "...");
                        String latest = MavenCentralClient.getLatestVersion(dep.getGroupId(), dep.getArtifactId());
                        if (latest != null) {
                            version = latest;
                        }
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
                    Notifier.error(project, "Already in the pom — not added again: "
                            + String.join(", ", duplicates));
                }
                if (toAdd.isEmpty()) {
                    return;
                }
                try {
                    PomUtils.addDependencies(pom, toAdd);
                } catch (IOException e) {
                    Notifier.error(project, "Failed to add dependencies: " + e.getMessage());
                    return;
                }
                int autoUpdateCount = 0;
                AutoUpdateService service = AutoUpdateService.getInstance();
                for (Dependency dep : toAdd) {
                    if (autoUpdateKeys.contains(dep.getKey())) {
                        service.setAutoUpdate(pom.getAbsolutePath(), dep.getKey(), true);
                        autoUpdateCount++;
                    }
                }
                Notifier.info(project, "Added " + toAdd.size() + " dependencies to pom.xml"
                        + (autoUpdateCount > 0 ? " (" + autoUpdateCount + " with auto update)" : "")
                        + ". Building...");
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
            return tableFields.size() + 3 + colOffset();
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
            if (columnIndex == deleteColumn()) {
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
                case AUTO_UPDATE -> pomFile != null && AutoUpdateService.getInstance()
                        .isAutoUpdate(pomFile.getAbsolutePath(), row.dependency.getKey());
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
            setToolTipText("Update this dependency: newest version or a custom one");
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
            return button;
        }

        @Override
        public @Nullable Object getCellEditorValue() {
            return null;
        }
    }
}

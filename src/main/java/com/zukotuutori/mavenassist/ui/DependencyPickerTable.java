package com.zukotuutori.mavenassist.ui;

import com.zukotuutori.mavenassist.model.Dependency;
import com.zukotuutori.mavenassist.services.DependencyManagerService;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.AbstractCellEditor;
import javax.swing.JButton;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;
import java.awt.Component;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Shared picker for "addable" dependencies (used by the Add Dependencies dialog and
 * the pom-creation view): searchable rows showing only a checkbox, the dependency's
 * <b>name</b> and a per-row <b>Show Details</b> button — everything else (coordinates,
 * versions) lives behind the details callback. Checked state survives filtering.
 *
 * <p>The pom-creation view enables an extra <b>Version</b> column whose button offers
 * "Newest version" (the default) or a custom version per dependency.</p>
 */
class DependencyPickerTable extends JBTable {

    private final int colCheck = 0;
    private final int colName = 1;
    /** -1 when the version column is disabled. */
    private final int colVersion;
    private final int colDetails;
    /** -1 when the delete column is disabled (e.g. in the template dialog). */
    private final int colDelete;

    private final List<Dependency> all = new ArrayList<>();
    private final List<Dependency> filtered = new ArrayList<>();
    private final Set<String> checkedKeys = new HashSet<>();
    /** groupId:artifactId → user-chosen version; absent = "use the newest". */
    private final Map<String, String> customVersions = new HashMap<>();
    private final PickModel model = new PickModel();
    private final Consumer<Dependency> detailsHandler;

    private String query = "";

    DependencyPickerTable(@NotNull Consumer<Dependency> detailsHandler) {
        this(detailsHandler, false, true);
    }

    DependencyPickerTable(@NotNull Consumer<Dependency> detailsHandler, boolean withVersionColumn) {
        this(detailsHandler, withVersionColumn, true);
    }

    DependencyPickerTable(@NotNull Consumer<Dependency> detailsHandler,
                          boolean withVersionColumn, boolean withDeleteColumn) {
        this.detailsHandler = detailsHandler;
        this.colVersion = withVersionColumn ? 2 : -1;
        this.colDetails = withVersionColumn ? 3 : 2;
        this.colDelete = withDeleteColumn ? colDetails + 1 : -1;
        setModel(model);
        TablePills.modernize(this);
        getTableHeader().setReorderingAllowed(false);
        getColumnModel().getColumn(colCheck).setMaxWidth(JBUI.scale(30));
        if (withVersionColumn) {
            VersionButtonRenderer versionRenderer = new VersionButtonRenderer();
            getColumnModel().getColumn(colVersion).setCellRenderer(versionRenderer);
            getColumnModel().getColumn(colVersion).setCellEditor(new VersionButtonEditor());
            getColumnModel().getColumn(colVersion).setMaxWidth(JBUI.scale(140));
        }
        DetailsButtonRenderer renderer = new DetailsButtonRenderer();
        getColumnModel().getColumn(colDetails).setCellRenderer(renderer);
        getColumnModel().getColumn(colDetails).setCellEditor(new DetailsButtonEditor());
        getColumnModel().getColumn(colDetails)
                .setMaxWidth(Math.max(JBUI.scale(120), renderer.getPreferredSize().width + JBUI.scale(8)));
        if (withDeleteColumn) {
            getColumnModel().getColumn(colDelete).setCellRenderer(
                    new TablePills.DeleteRenderer("Delete this dependency from the manager (all projects)"));
            getColumnModel().getColumn(colDelete).setMaxWidth(JBUI.scale(44));

            // Click on the trash cell deletes the dependency from the manager (after confirmation).
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    int row = rowAtPoint(e.getPoint());
                    int column = columnAtPoint(e.getPoint());
                    if (row >= 0 && column >= 0 && convertColumnIndexToModel(column) == colDelete) {
                        confirmAndDeleteFromManager(convertRowIndexToModel(row));
                    }
                }
            });
        }
    }

    private void confirmAndDeleteFromManager(int modelRow) {
        if (modelRow < 0 || modelRow >= filtered.size()) {
            return;
        }
        Dependency d = filtered.get(modelRow);
        int answer = Messages.showYesNoDialog(this,
                "Delete \"" + displayName(d) + "\" (" + d.getKey() + ") from the dependency manager?\n"
                        + "It disappears from all dependency lists, in every project.",
                "Delete From Manager", Messages.getWarningIcon());
        if (answer != Messages.YES) {
            return;
        }
        DependencyManagerService.getInstance().removeDependency(d);
        all.removeIf(dep -> dep.getName().equalsIgnoreCase(d.getName()) && dep.getKey().equals(d.getKey()));
        checkedKeys.remove(d.getKey());
        customVersions.remove(d.getKey());
        applyFilter();
    }

    /** Live-filters this table from the search field's text. */
    void bindSearch(@NotNull SearchTextField searchField) {
        searchField.getTextEditor().getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                setFilter(searchField.getText());
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                setFilter(searchField.getText());
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                setFilter(searchField.getText());
            }
        });
    }

    /** Replaces the content; already-checked dependencies stay checked (matched by key). */
    void setDependencies(@NotNull List<Dependency> dependencies) {
        all.clear();
        all.addAll(dependencies);
        applyFilter();
    }

    void setFilter(@Nullable String query) {
        this.query = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        applyFilter();
    }

    private void applyFilter() {
        filtered.clear();
        for (Dependency d : all) {
            if (query.isEmpty()
                    || d.getKey().toLowerCase(Locale.ROOT).contains(query)
                    || d.getName().toLowerCase(Locale.ROOT).contains(query)) {
                filtered.add(d);
            }
        }
        model.fireTableDataChanged();
    }

    /** The checked dependencies, one per groupId:artifactId (duplicate names collapse). */
    @NotNull List<Dependency> getCheckedDependencies() {
        Map<String, Dependency> byKey = new LinkedHashMap<>();
        for (Dependency d : all) {
            if (checkedKeys.contains(d.getKey())) {
                byKey.putIfAbsent(d.getKey(), d);
            }
        }
        return new ArrayList<>(byKey.values());
    }

    /** groupId:artifactId → the version the user pinned via the Version button. */
    @NotNull Map<String, String> getCustomVersions() {
        return new HashMap<>(customVersions);
    }

    /** Pins a version for the dependency; pinning implies wanting it, so the row is ticked too. */
    void setCustomVersion(@NotNull String dependencyKey, @NotNull String version) {
        customVersions.put(dependencyKey, version);
        checkedKeys.add(dependencyKey);
        model.fireTableDataChanged();
    }

    /** Back to "use the newest version". */
    void clearCustomVersion(@NotNull String dependencyKey) {
        customVersions.remove(dependencyKey);
        model.fireTableDataChanged();
    }

    /** Ticks every dependency currently visible (i.e. matching the active filter). */
    void checkAllVisible() {
        for (Dependency d : filtered) {
            checkedKeys.add(d.getKey());
        }
        model.fireTableDataChanged();
    }

    /** Unticks everything (also rows currently hidden by the filter). */
    void checkNone() {
        checkedKeys.clear();
        model.fireTableDataChanged();
    }

    void setChecked(@NotNull String dependencyKey, boolean checked) {
        if (checked) {
            checkedKeys.add(dependencyKey);
        } else {
            checkedKeys.remove(dependencyKey);
        }
        model.fireTableDataChanged();
    }

    /** The dependency of the selected row, or null. */
    @Nullable Dependency getSelectedDependency() {
        int viewRow = getSelectedRow();
        if (viewRow < 0) {
            return null;
        }
        int modelRow = convertRowIndexToModel(viewRow);
        return modelRow < filtered.size() ? filtered.get(modelRow) : null;
    }

    void refresh() {
        model.fireTableDataChanged();
    }

    static @NotNull String displayName(@NotNull Dependency d) {
        return d.getName().isEmpty() ? d.getArtifactId() : d.getName();
    }

    /** The label of a row's Version button: the pinned version, or "Newest". */
    private @NotNull String versionLabel(@NotNull Dependency d) {
        String custom = customVersions.get(d.getKey());
        return custom == null ? "Newest" : custom;
    }

    /** "Newest version" clears the pin, "Custom version..." asks for one. */
    private void showVersionMenu(@NotNull Dependency d, int viewRow) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem newest = new JMenuItem("Newest version");
        newest.addActionListener(e -> clearCustomVersion(d.getKey()));
        menu.add(newest);
        JMenuItem custom = new JMenuItem("Custom version...");
        custom.addActionListener(e -> {
            String input = Messages.showInputDialog(this,
                    "Custom version for " + d.getKey() + ":\n(leave empty to use the newest version)",
                    "Custom Version", null, customVersions.get(d.getKey()), null);
            if (input == null) {
                return; // cancelled
            }
            if (input.trim().isEmpty()) {
                clearCustomVersion(d.getKey());
            } else {
                setCustomVersion(d.getKey(), input.trim());
            }
        });
        menu.add(custom);
        java.awt.Rectangle cell = getCellRect(viewRow, colVersion, true);
        menu.show(this, cell.x, cell.y + cell.height);
    }

    // ---------------------------------------------------------------- model

    /** Checkbox + name (+ optional version pin); everything else lives behind "Show Details". */
    private class PickModel extends AbstractTableModel {

        @Override
        public int getRowCount() {
            return filtered.size();
        }

        @Override
        public int getColumnCount() {
            return (colDelete >= 0 ? colDelete : colDetails) + 1;
        }

        @Override
        public String getColumnName(int column) {
            if (column == colName) {
                return "Name";
            }
            if (column == colVersion) {
                return "Version";
            }
            return "";
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == colCheck ? Boolean.class : Object.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == colCheck || columnIndex == colDetails || columnIndex == colVersion;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Dependency d = filtered.get(rowIndex);
            if (columnIndex == colCheck) {
                return checkedKeys.contains(d.getKey());
            }
            if (columnIndex == colName) {
                return displayName(d);
            }
            if (columnIndex == colVersion) {
                return versionLabel(d);
            }
            if (columnIndex == colDetails) {
                return "Details";
            }
            return "";
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            if (columnIndex == colCheck && rowIndex < filtered.size()) {
                Dependency d = filtered.get(rowIndex);
                if (Boolean.TRUE.equals(aValue)) {
                    checkedKeys.add(d.getKey());
                } else {
                    checkedKeys.remove(d.getKey());
                }
            }
        }
    }

    /** Same look as the main table's details button. */
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
                if (row >= 0 && row < filtered.size()) {
                    detailsHandler.accept(filtered.get(row));
                }
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

    /** Shows the pinned version (or "Newest") and opens the newest/custom menu. */
    private class VersionButtonRenderer extends JButton implements TableCellRenderer {
        VersionButtonRenderer() {
            setToolTipText("Choose the version this dependency is created with");
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            setText(String.valueOf(value));
            return this;
        }
    }

    private class VersionButtonEditor extends AbstractCellEditor implements TableCellEditor {
        private final JButton button = new JButton();
        private int editedRow = -1;

        VersionButtonEditor() {
            button.addActionListener(e -> {
                int row = editedRow;
                fireEditingStopped();
                if (row >= 0 && row < filtered.size()) {
                    showVersionMenu(filtered.get(row), row);
                }
            });
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected,
                                                     int row, int column) {
            editedRow = row;
            button.setText(String.valueOf(value));
            return button;
        }

        @Override
        public @Nullable Object getCellEditorValue() {
            return null;
        }
    }
}

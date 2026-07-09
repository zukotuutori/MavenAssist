package com.zukotuutori.mavenassist.ui;

import com.zukotuutori.mavenassist.model.Dependency;
import com.zukotuutori.mavenassist.services.DependencyManagerService;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.JBColor;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.table.JBTable;
import com.intellij.util.IconUtil;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.AbstractCellEditor;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;
import java.awt.Color;
import java.awt.Component;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Shared picker for "addable" dependencies (used by the Add Dependencies dialog and
 * the pom-creation view): searchable rows showing only a checkbox, the dependency's
 * <b>name</b> and a per-row <b>Show Details</b> button — everything else (coordinates,
 * versions) lives behind the details callback. Checked state survives filtering.
 */
class DependencyPickerTable extends JBTable {

    private static final int COL_CHECK = 0;
    private static final int COL_NAME = 1;
    private static final int COL_DETAILS = 2;
    private static final int COL_DELETE = 3;

    private final List<Dependency> all = new ArrayList<>();
    private final List<Dependency> filtered = new ArrayList<>();
    private final Set<String> checkedKeys = new HashSet<>();
    private final PickModel model = new PickModel();
    private final Consumer<Dependency> detailsHandler;

    private String query = "";

    DependencyPickerTable(@NotNull Consumer<Dependency> detailsHandler) {
        this.detailsHandler = detailsHandler;
        setModel(model);
        TablePills.modernize(this);
        getTableHeader().setReorderingAllowed(false);
        getColumnModel().getColumn(COL_CHECK).setMaxWidth(JBUI.scale(30));
        DetailsButtonRenderer renderer = new DetailsButtonRenderer();
        getColumnModel().getColumn(COL_DETAILS).setCellRenderer(renderer);
        getColumnModel().getColumn(COL_DETAILS).setCellEditor(new DetailsButtonEditor());
        getColumnModel().getColumn(COL_DETAILS)
                .setMaxWidth(Math.max(JBUI.scale(120), renderer.getPreferredSize().width + JBUI.scale(8)));
        getColumnModel().getColumn(COL_DELETE).setCellRenderer(
                new TablePills.DeleteRenderer("Delete this dependency from the manager (all projects)"));
        getColumnModel().getColumn(COL_DELETE).setMaxWidth(JBUI.scale(44));

        // Click on the trash cell deletes the dependency from the manager (after confirmation).
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int row = rowAtPoint(e.getPoint());
                int column = columnAtPoint(e.getPoint());
                if (row >= 0 && column >= 0 && convertColumnIndexToModel(column) == COL_DELETE) {
                    confirmAndDeleteFromManager(convertRowIndexToModel(row));
                }
            }
        });
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
        DependencyManagerService.getInstance().removeDependency(d.getKey());
        all.removeIf(dep -> dep.getKey().equals(d.getKey()));
        checkedKeys.remove(d.getKey());
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

    @NotNull List<Dependency> getCheckedDependencies() {
        List<Dependency> result = new ArrayList<>();
        for (Dependency d : all) {
            if (checkedKeys.contains(d.getKey())) {
                result.add(d);
            }
        }
        return result;
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

    // ---------------------------------------------------------------- model

    /** Checkbox + name only; everything else lives behind "Show Details". */
    private class PickModel extends AbstractTableModel {
        private final String[] columns = {"", "Name", "", ""};

        @Override
        public int getRowCount() {
            return filtered.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == COL_CHECK ? Boolean.class : Object.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == COL_CHECK || columnIndex == COL_DETAILS;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Dependency d = filtered.get(rowIndex);
            return switch (columnIndex) {
                case COL_CHECK -> checkedKeys.contains(d.getKey());
                case COL_NAME -> displayName(d);
                case COL_DETAILS -> "Details";
                default -> "";
            };
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            if (columnIndex == COL_CHECK && rowIndex < filtered.size()) {
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
}

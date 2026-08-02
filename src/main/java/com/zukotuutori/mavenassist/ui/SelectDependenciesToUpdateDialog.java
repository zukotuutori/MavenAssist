package com.zukotuutori.mavenassist.ui;

import com.zukotuutori.mavenassist.model.Dependency;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Lets the user choose which dependencies the bulk update should process. */
final class SelectDependenciesToUpdateDialog extends DialogWrapper {

    private final List<Dependency> dependencies;
    private final Set<String> selectedKeys = new HashSet<>();
    private final AbstractTableModel model;
    private final ThemedTable table;

    SelectDependenciesToUpdateDialog(@NotNull Project project,
                                     @NotNull List<Dependency> dependencies) {
        super(project, true);
        this.dependencies = List.copyOf(dependencies);
        this.model = new SelectionModel();
        this.table = new ThemedTable(model);
        setTitle("Select Dependencies to Update");
        setOKButtonText("Update Selected");
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        panel.setPreferredSize(new Dimension(JBUI.scale(520), JBUI.scale(360)));
        panel.add(new JBLabel("Select the dependencies that should be updated to their newest version:"),
                BorderLayout.NORTH);

        table.getTableHeader().setReorderingAllowed(false);
        table.getColumnModel().getColumn(0).setMaxWidth(JBUI.scale(70));
        panel.add(TablePills.scroll(table), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
        JButton selectAll = new JButton("Select All");
        selectAll.addActionListener(e -> {
            for (Dependency dependency : dependencies) {
                selectedKeys.add(dependency.getKey());
            }
            model.fireTableDataChanged();
        });
        buttons.add(selectAll);

        JButton selectNone = new JButton("Select None");
        selectNone.addActionListener(e -> {
            selectedKeys.clear();
            model.fireTableDataChanged();
        });
        buttons.add(selectNone);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        return selectedKeys.isEmpty()
                ? new ValidationInfo("Select at least one dependency.", table)
                : null;
    }

    @NotNull List<Dependency> getSelectedDependencies() {
        List<Dependency> selected = new ArrayList<>();
        for (Dependency dependency : dependencies) {
            if (selectedKeys.contains(dependency.getKey())) {
                selected.add(dependency);
            }
        }
        return selected;
    }

    private final class SelectionModel extends AbstractTableModel {

        private static final String[] COLUMNS = {"Update", "Dependency", "Current Version"};

        @Override
        public int getRowCount() {
            return dependencies.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 0 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == 0;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Dependency dependency = dependencies.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> selectedKeys.contains(dependency.getKey());
                case 1 -> dependency.getName().isEmpty()
                        ? dependency.getKey()
                        : dependency.getName() + " (" + dependency.getKey() + ")";
                case 2 -> dependency.getVersion().isEmpty()
                        ? "(managed)"
                        : dependency.getVersion();
                default -> "";
            };
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            if (columnIndex != 0 || rowIndex < 0 || rowIndex >= dependencies.size()) {
                return;
            }
            String key = dependencies.get(rowIndex).getKey();
            if (Boolean.TRUE.equals(value)) {
                selectedKeys.add(key);
            } else {
                selectedKeys.remove(key);
            }
            fireTableCellUpdated(rowIndex, columnIndex);
        }
    }
}

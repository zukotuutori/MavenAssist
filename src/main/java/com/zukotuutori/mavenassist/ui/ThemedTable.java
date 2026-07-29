package com.zukotuutori.mavenassist.ui;

import com.intellij.ui.table.JBTable;
import org.jetbrains.annotations.NotNull;

import javax.swing.JTable;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableModel;
import java.awt.Color;
import java.awt.Component;

/**
 * A {@link JBTable} that sits on a {@link SectionBox} card instead of on the theme's
 * own list surface. Selection, hover and focus keep the IDE's colors; only the two
 * row backgrounds are re-based on the card, so the table blends into it in every theme.
 */
class ThemedTable extends JBTable {

    ThemedTable() {
        installSafeTextRenderer();
        TablePills.modernize(this);
    }

    ThemedTable(@NotNull TableModel model) {
        super(model);
        installSafeTextRenderer();
        TablePills.modernize(this);
    }

    @Override
    protected Color getStripeColor() {
        return Theme.CARD_STRIPE;
    }

    private void installSafeTextRenderer() {
        setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                           boolean focused, int row, int column) {
                // Passing the raw value to Swing first is already enough to activate its HTML
                // renderer, so give the superclass only the escaped representation.
                return super.getTableCellRendererComponent(table,
                        UiText.html(value == null ? "" : value), selected, focused, row, column);
            }
        });
    }
}

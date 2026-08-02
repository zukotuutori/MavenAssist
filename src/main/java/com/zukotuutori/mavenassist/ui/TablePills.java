package com.zukotuutori.mavenassist.ui;

import com.intellij.icons.AllIcons;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.IconUtil;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JTable;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * Shared modern table styling: striped, grid-less tables and rounded colored
 * "pill" cells (auto-update state, delete buttons) that keep their color under
 * hover and selection because they paint themselves on top of the row.
 */
final class TablePills {

    static final JBColor AUTO_ON = new JBColor(new Color(0x36A159), new Color(0x4E9E62));
    static final JBColor AUTO_OFF = new JBColor(new Color(0xD25252), new Color(0xB4534E));
    static final JBColor DELETE = new JBColor(new Color(0xDB5860), new Color(0xC75450));
    static final Icon TRASH_WHITE = IconUtil.colorize(AllIcons.Actions.GC, Color.WHITE);

    private TablePills() {
    }

    /**
     * Applies the shared modern look: striped rows, no grid lines, roomier rows,
     * and so the table blends into the card it sits on instead of painting the
     * theme's stark list surface, the card background.
     */
    static void modernize(@NotNull JBTable table) {
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setRowHeight(JBUI.scale(28));
        table.setStriped(true);
        Theme.applyCardBackground(table);
        JTableHeader header = table.getTableHeader();
        if (header != null) {
            Theme.applyCardBackground(header);
        }
    }

    /** Wraps a table in a scroll pane whose borders and empty area match the card too. */
    static @NotNull JBScrollPane scroll(@NotNull JBTable table) {
        JBScrollPane scrollPane = new JBScrollPane(table);
        scrollPane.setBorder(JBUI.Borders.empty());
        scrollPane.setViewportBorder(JBUI.Borders.empty());
        scrollPane.setOpaque(false);
        scrollPane.getViewport().setOpaque(false);
        return scrollPane;
    }

    /** A rounded colored capsule painted with a small inset; optionally with a centered icon. */
    static class PillRenderer extends JComponent implements TableCellRenderer {
        private Color color = DELETE;
        private Icon icon;

        void setPill(@NotNull Color color, @Nullable Icon icon, @Nullable String tooltip) {
            this.color = color;
            this.icon = icon;
            setToolTipText(tooltip);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            // JBTable marks renderers opaque to paint the row stripe/hover, which obliges us to
            // fill our bounds; otherwise the margin around the pill keeps whatever was there.
            if (isOpaque()) {
                g2.setColor(getBackground());
                g2.fillRect(0, 0, getWidth(), getHeight());
            }
            int inset = JBUI.scale(4);
            int arc = JBUI.scale(10);
            g2.setColor(color);
            g2.fillRoundRect(inset, inset, getWidth() - 2 * inset, getHeight() - 2 * inset, arc, arc);
            if (icon != null) {
                icon.paintIcon(this, g2, (getWidth() - icon.getIconWidth()) / 2,
                        (getHeight() - icon.getIconHeight()) / 2);
            }
            g2.dispose();
        }
    }

    /** Green/red capsule for the auto-update flag. */
    static class AutoUpdateRenderer extends PillRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            boolean on = Boolean.TRUE.equals(value);
            setPill(on ? AUTO_ON : AUTO_OFF, null,
                    on ? "Auto update is ON. Click to review turning it off."
                            : "Auto update is OFF. Click to review turning it on.");
            return this;
        }
    }

    /** Red capsule with a white trash icon. */
    static class DeleteRenderer extends PillRenderer {
        private final String tooltip;

        DeleteRenderer(@NotNull String tooltip) {
            this.tooltip = tooltip;
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            setPill(DELETE, TRASH_WHITE, tooltip);
            return this;
        }
    }
}

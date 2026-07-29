package com.zukotuutori.mavenassist.ui;

import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * A rounded card with a big, high-contrast headline (optionally with an icon).
 * used to subdivide the plugin page into visual sections (directory/pom info,
 * dependency table, actions). Surface, outline and headline all come from
 * {@link Theme}, i.e. from the IDE's active look-and-feel, so the card matches
 * whatever theme is in use and recolors when the theme changes.
 */
class SectionBox extends JPanel {

    SectionBox(@NotNull String title, @NotNull JComponent content) {
        this(title, null, content);
    }

    SectionBox(@NotNull String title, @Nullable Icon icon, @NotNull JComponent content) {
        super(new BorderLayout(0, JBUI.scale(8)));
        setOpaque(false);
        setBorder(JBUI.Borders.empty(10, 12));

        JBLabel titleLabel = new JBLabel(title);
        titleLabel.setForeground(Theme.CARD_TITLE);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, (float) JBUI.scaleFontSize(15f)));
        if (icon != null) {
            titleLabel.setIcon(icon);
            titleLabel.setIconTextGap(JBUI.scale(6));
        }
        add(titleLabel, BorderLayout.NORTH);

        content.setOpaque(false);
        add(content, BorderLayout.CENTER);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int arc = JBUI.scale(16);
        g2.setColor(Theme.CARD_BACKGROUND);
        g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
        g2.setColor(Theme.CARD_BORDER);
        g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
        g2.dispose();
        super.paintComponent(g);
    }
}

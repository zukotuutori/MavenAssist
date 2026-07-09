package com.zukotuutori.mavenassist.ui;

import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * A rounded gray card with a big, high-contrast headline (optionally with an icon) —
 * used to subdivide the plugin page into visual sections (directory/pom info,
 * dependency table, actions). The headline is white in the dark theme (near-black
 * in the light theme).
 */
class SectionBox extends JPanel {

    private static final JBColor BACKGROUND = new JBColor(new Color(0xF6F7F8), new Color(0x3A3D3F));
    private static final JBColor BORDER = new JBColor(new Color(0xDDDFE1), new Color(0x4C4F51));
    private static final JBColor TITLE = new JBColor(new Color(0x111111), new Color(0xFFFFFF));

    SectionBox(@NotNull String title, @NotNull JComponent content) {
        this(title, null, content);
    }

    SectionBox(@NotNull String title, @Nullable Icon icon, @NotNull JComponent content) {
        super(new BorderLayout(0, JBUI.scale(8)));
        setOpaque(false);
        setBorder(JBUI.Borders.empty(10, 12));

        JBLabel titleLabel = new JBLabel(title);
        titleLabel.setForeground(TITLE);
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
        g2.setColor(BACKGROUND);
        g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
        g2.setColor(BORDER);
        g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
        g2.dispose();
        super.paintComponent(g);
    }
}

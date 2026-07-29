package com.zukotuutori.mavenassist.ui;

import com.intellij.ui.ColorUtil;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.AbstractButton;
import javax.swing.JComponent;
import java.awt.Color;

/**
 * Every color the plugin paints itself with is derived from the IDE's current
 * look-and-feel here instead of being a fixed light/dark pair, so the tool window
 * follows whatever theme is active, including third-party and custom themes.
 *
 * <p>All constants are {@link JBColor#lazy} producers: they re-read the LAF on
 * every paint, so switching the IDE theme recolors the plugin immediately.</p>
 */
final class Theme {

    private Theme() {
    }

    /** The plugin page's background, exactly as used by every other IDE tool window. */
    static final JBColor WINDOW_BACKGROUND = JBColor.lazy(UIUtil::getPanelBackground);

    /**
     * The slightly raised surface of a {@link SectionBox}, derived from the window
     * background so it stays one small step lighter in any theme.
     */
    static final JBColor CARD_BACKGROUND = JBColor.lazy(Theme::card);

    /**
     * Alternating row color for tables sitting on a card. The theme's own stripe color
     * is tuned for the theme's list surface, which the tables no longer use, so it is
     * derived from the card instead: the same subtle step with the correct base.
     */
    static final JBColor CARD_STRIPE = JBColor.lazy(() ->
            ColorUtil.mix(card(), UIUtil.getPanelBackground(), 0.45));

    /** Card outline; follows the theme's standard component border. */
    static final JBColor CARD_BORDER = JBColor.namedColor("Component.borderColor",
            JBColor.lazy(() -> ColorUtil.mix(card(), UIUtil.getLabelForeground(), 0.2)));

    /** Card headline; the theme's normal label color, so contrast is always right. */
    static final JBColor CARD_TITLE = JBColor.lazy(UIUtil::getLabelForeground);

    /** The theme's default ("primary") button fill, used for the blue accent buttons. */
    static final JBColor ACCENT_BACKGROUND =
            JBColor.namedColor("Button.default.startBackground", new JBColor(0x3574F0, 0x365880));

    /** Text/icon color that goes on top of {@link #ACCENT_BACKGROUND}. */
    static final JBColor ACCENT_FOREGROUND =
            JBColor.namedColor("Button.default.foreground", new JBColor(0xFFFFFF, 0xFFFFFF));

    /** Text/icon color that goes on top of the red destructive buttons and pills. */
    static final JBColor ON_DESTRUCTIVE = new JBColor(0xFFFFFF, 0xFFFFFF);

    private static @NotNull Color card() {
        Color base = UIUtil.getPanelBackground();
        // A small constant lift toward white reads as "raised card" at any base darkness.
        // (A multiplicative step, as ColorUtil.brighter does, over-brightens light bases.)
        return ColorUtil.mix(base, Color.WHITE, JBColor.isBright() ? 0.55 : 0.06);
    }

    /** Paints the component with the tool window background, matching the rest of the IDE. */
    static void applyWindowBackground(@NotNull JComponent component) {
        component.setOpaque(true);
        component.setBackground(WINDOW_BACKGROUND);
    }

    /** Makes a list/table blend into the card it sits on rather than painting its own surface. */
    static void applyCardBackground(@NotNull JComponent component) {
        component.setOpaque(true);
        component.setBackground(CARD_BACKGROUND);
    }

    /** Styles a button as the theme's primary action (blue in the stock themes). */
    static void applyAccent(@NotNull AbstractButton button) {
        button.putClientProperty("JButton.backgroundColor", ACCENT_BACKGROUND);
        button.setForeground(ACCENT_FOREGROUND);
    }
}

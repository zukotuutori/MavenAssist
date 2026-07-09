package com.zukotuutori.mavenassist.ui;

import com.zukotuutori.mavenassist.services.UiCustomizationService.ButtonId;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;

/**
 * Small shared helpers for the customizable button stacks: BoxLayout-friendly
 * wrappers used by both the real page and the customize preview, and the client
 * property that tags a preview button with its {@link ButtonId} so the drag layer
 * can identify it.
 */
final class UiEditSupport {

    private static final String PROP_ID = "mavenAssist.buttonId";

    private UiEditSupport() {
    }

    /**
     * Wraps a button for use in a BoxLayout stack: keeps its preferred height
     * (so per-button size customization survives) and adds the gap between buttons.
     * A button with a fixed custom width keeps that width instead of filling the row.
     */
    static @NotNull JPanel wrap(@NotNull JButton button, boolean vertical) {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.setBorder(vertical ? JBUI.Borders.emptyBottom(4) : JBUI.Borders.emptyRight(4));
        wrapper.add(button, BorderLayout.CENTER);
        wrapper.setAlignmentX(0f);
        applyWrapperSizing(wrapper, button, vertical);
        return wrapper;
    }

    /** (Re-)derives the wrapper's stretch limits from the button's current preferred size. */
    static void applyWrapperSizing(@NotNull JPanel wrapper, @NotNull JButton button, boolean vertical) {
        wrapper.setPreferredSize(null);
        Dimension pref = wrapper.getPreferredSize();
        boolean fixedWidth = Boolean.TRUE.equals(
                button.getClientProperty(com.zukotuutori.mavenassist.services.UiCustomizationService.FIXED_WIDTH_PROPERTY));
        int maxWidth = fixedWidth ? pref.width : Integer.MAX_VALUE;
        wrapper.setMaximumSize(new Dimension(maxWidth, vertical ? pref.height : Integer.MAX_VALUE));
    }

    /** Marks a preview button as draggable/stylable, carrying its identity. */
    static void tag(@NotNull JButton button, @NotNull ButtonId id) {
        button.putClientProperty(PROP_ID, id);
    }

    static @Nullable ButtonId buttonId(@Nullable Component component) {
        return component instanceof JButton button ? (ButtonId) button.getClientProperty(PROP_ID) : null;
    }

    /** The ButtonId of the button inside a stack wrapper, or null for gaps/hints. */
    static @Nullable ButtonId wrapperId(@Nullable Component wrapper) {
        if (wrapper instanceof Container container && container.getComponentCount() > 0) {
            return buttonId(container.getComponent(0));
        }
        return null;
    }
}

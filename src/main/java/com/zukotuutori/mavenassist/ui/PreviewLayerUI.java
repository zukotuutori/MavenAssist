package com.zukotuutori.mavenassist.ui;

import com.zukotuutori.mavenassist.services.UiCustomizationService;
import com.zukotuutori.mavenassist.services.UiCustomizationService.ButtonId;
import com.zukotuutori.mavenassist.services.UiCustomizationService.Group;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLayer;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.plaf.LayerUI;
import java.awt.AWTEvent;
import java.awt.AlphaComposite;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Drag-and-drop engine for the customize preview, installed as a {@link JLayer}
 * over the whole preview area. Dragging a tagged button lifts it into a
 * semi-transparent ghost that sticks to the mouse; an animated gap opens at the
 * spot where it would land and glides between positions as you move. Buttons can
 * be dropped into <b>any</b> section (top bar, table buttons, details-dialog
 * buttons) — on drop the full layout of every section is persisted. A plain
 * click (no movement) opens the style editor for the button instead.
 */
class PreviewLayerUI extends LayerUI<JComponent> {

    /** One droppable button section of the preview. */
    record DropStack(@NotNull JPanel panel, @NotNull Group group, boolean vertical) {
    }

    /** Which edge of a button is being dragged to resize it. */
    private enum ResizeMode {
        EAST, SOUTH, CORNER
    }

    private static final int DRAG_THRESHOLD = 5;
    private static final int ANIMATION_MS = 140;
    /** Pixels from the right/bottom edge of a button that act as a resize handle. */
    private static final int RESIZE_MARGIN = 7;

    private final List<DropStack> stacks;

    private JLayer<? extends JComponent> layer;

    // Per-drag state.
    private @Nullable JButton pressedButton;
    private @Nullable ButtonId pressedId;
    private @Nullable Point pressPoint;
    private boolean dragging;
    private @Nullable BufferedImage ghostImage;
    private @Nullable Point ghostPos;
    private @Nullable Point grabOffset;
    private @Nullable Dimension draggedButtonSize;
    private @Nullable AnimatedGap gap;
    private @Nullable DropStack gapStack;
    /**
     * The dragged button's wrapper. It must stay in the hierarchy for the whole drag
     * (a detached component stops receiving mouse events), so it is made invisible
     * and zero-sized instead of removed; the animated gap provides all the visuals.
     */
    private @Nullable Component draggedWrapper;

    // Per-resize state (pressing near a button's right/bottom edge resizes instead of moving).
    private @Nullable ResizeMode resizeMode;
    private @Nullable Dimension resizeStartSize;
    private @Nullable Dimension resizeStartPref;
    private boolean resized;

    PreviewLayerUI(@NotNull List<DropStack> stacks) {
        this.stacks = stacks;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void installUI(JComponent c) {
        super.installUI(c);
        layer = (JLayer<? extends JComponent>) c;
        layer.setLayerEventMask(AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK);
    }

    @Override
    public void uninstallUI(JComponent c) {
        ((JLayer<?>) c).setLayerEventMask(0);
        super.uninstallUI(c);
    }

    /** Paints the floating ghost of the dragged button on top of everything. */
    @Override
    public void paint(Graphics g, JComponent c) {
        super.paint(g, c);
        if (dragging && ghostImage != null && ghostPos != null) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.85f));
            g2.drawImage(ghostImage, ghostPos.x, ghostPos.y, null);
            g2.dispose();
        }
    }

    @Override
    protected void processMouseEvent(MouseEvent e, JLayer<? extends JComponent> l) {
        Point p = pointInLayer(e);
        switch (e.getID()) {
            case MouseEvent.MOUSE_PRESSED -> onPress(e, p);
            case MouseEvent.MOUSE_RELEASED -> onRelease(e, p);
            default -> {
            }
        }
    }

    @Override
    protected void processMouseMotionEvent(MouseEvent e, JLayer<? extends JComponent> l) {
        Point p = pointInLayer(e);
        if (e.getID() == MouseEvent.MOUSE_DRAGGED) {
            onDrag(p);
        } else if (e.getID() == MouseEvent.MOUSE_MOVED) {
            updateCursor(p);
        }
    }

    // ---------------------------------------------------------------- mouse handling

    private void onPress(@NotNull MouseEvent e, @NotNull Point p) {
        if (!SwingUtilities.isLeftMouseButton(e)) {
            return;
        }
        JButton button = buttonAt(p);
        if (button != null) {
            pressedButton = button;
            pressedId = UiEditSupport.buttonId(button);
            pressPoint = p;
            dragging = false;
            resized = false;
            resizeMode = resizeModeAt(button, p);
            resizeStartSize = button.getSize();
            resizeStartPref = button.getPreferredSize();
            e.consume();
        }
    }

    private void onDrag(@NotNull Point p) {
        if (pressedButton == null || pressPoint == null) {
            return;
        }
        if (resizeMode != null) {
            resizeTo(p);
            return;
        }
        if (!dragging) {
            if (p.distance(pressPoint) < DRAG_THRESHOLD) {
                return;
            }
            startDrag(p);
        }
        if (grabOffset != null) {
            ghostPos = new Point(p.x - grabOffset.x, p.y - grabOffset.y);
        }
        DropStack target = stackAt(p);
        if (target != null) {
            placeGap(target, insertSlotAt(target, p));
        }
        layer.repaint();
    }

    private void onRelease(@NotNull MouseEvent e, @NotNull Point p) {
        if (pressedButton == null) {
            return;
        }
        if (dragging) {
            commitDrop();
        } else if (resized) {
            commitResize();
        } else if (pressedId != null && buttonAt(p) == pressedButton) {
            new ButtonStyleDialog(pressedButton, pressedId).show();
        }
        pressedButton = null;
        pressedId = null;
        pressPoint = null;
        dragging = false;
        resized = false;
        resizeMode = null;
        ghostImage = null;
        ghostPos = null;
        e.consume();
        layer.repaint();
        updateCursor(p);
    }

    private void updateCursor(@NotNull Point p) {
        Cursor cursor = Cursor.getDefaultCursor();
        if (dragging || resized) {
            cursor = dragging ? Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR) : resizeCursor(resizeMode);
        } else {
            JButton button = buttonAt(p);
            if (button != null) {
                ResizeMode mode = resizeModeAt(button, p);
                cursor = mode != null ? resizeCursor(mode) : Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR);
            }
        }
        layer.getGlassPane().setCursor(cursor);
    }

    private static @NotNull Cursor resizeCursor(@Nullable ResizeMode mode) {
        return Cursor.getPredefinedCursor(mode == ResizeMode.EAST ? Cursor.E_RESIZE_CURSOR
                : mode == ResizeMode.SOUTH ? Cursor.S_RESIZE_CURSOR : Cursor.SE_RESIZE_CURSOR);
    }

    // ---------------------------------------------------------------- edge resizing

    /** The resize handle at the point, or null when the press means move/click. */
    private @Nullable ResizeMode resizeModeAt(@NotNull JButton button, @NotNull Point pInLayer) {
        Point p = SwingUtilities.convertPoint(layer, pInLayer, button);
        boolean east = p.x >= button.getWidth() - RESIZE_MARGIN;
        boolean south = p.y >= button.getHeight() - RESIZE_MARGIN;
        if (east && south) {
            return ResizeMode.CORNER;
        }
        if (east) {
            return ResizeMode.EAST;
        }
        if (south) {
            return ResizeMode.SOUTH;
        }
        return null;
    }

    /** Live resize while the edge is being dragged; the layout follows immediately. */
    private void resizeTo(@NotNull Point p) {
        JButton button = pressedButton;
        if (button == null || pressPoint == null || resizeStartSize == null || resizeStartPref == null) {
            return;
        }
        resized = true;
        int width = clamp(resizeStartSize.width + (p.x - pressPoint.x), 24, 600);
        int height = clamp(resizeStartSize.height + (p.y - pressPoint.y), 18, 200);
        button.setPreferredSize(new Dimension(
                resizeMode == ResizeMode.SOUTH ? resizeStartPref.width : width,
                resizeMode == ResizeMode.EAST ? resizeStartPref.height : height));
        if (resizeMode != ResizeMode.SOUTH) {
            button.putClientProperty(UiCustomizationService.FIXED_WIDTH_PROPERTY, true);
        }
        if (button.getParent() instanceof JPanel wrapper) {
            DropStack stack = stackOf(wrapper);
            UiEditSupport.applyWrapperSizing(wrapper, button, stack == null || stack.vertical());
            if (stack != null) {
                stack.panel().revalidate();
                stack.panel().repaint();
            }
        }
        updateCursor(p);
    }

    /** Saves the dragged size (unscaled, so it survives different UI scale factors). */
    private void commitResize() {
        JButton button = pressedButton;
        if (button == null || pressedId == null) {
            return;
        }
        UiCustomizationService service = UiCustomizationService.getInstance();
        UiCustomizationService.ButtonStyle style = service.getStyle(pressedId);
        float scale = com.intellij.util.ui.JBUI.scale(1000) / 1000f;
        Dimension pref = button.getPreferredSize();
        if (resizeMode != ResizeMode.SOUTH) {
            style.width = Math.round(pref.width / scale);
        }
        if (resizeMode != ResizeMode.EAST) {
            style.height = Math.round(pref.height / scale);
        }
        service.setStyle(pressedId, style);
        service.notifyCustomizationChanged();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    // ---------------------------------------------------------------- drag lifecycle

    /** Lifts the button out of its stack: ghost follows the mouse, a full-size gap stays behind. */
    private void startDrag(@NotNull Point p) {
        dragging = true;
        JButton button = pressedButton;
        Component wrapper = button.getParent();
        DropStack source = stackOf(wrapper);
        if (source == null) {
            dragging = false;
            return;
        }
        draggedButtonSize = button.getSize();
        ghostImage = snapshot(button);
        Point buttonInLayer = SwingUtilities.convertPoint(button.getParent(), button.getLocation(), layer);
        grabOffset = new Point(p.x - buttonInLayer.x, p.y - buttonInLayer.y);
        ghostPos = buttonInLayer;

        // Collapse the wrapper in place (kept attached as the mouse-event anchor)
        // and open the animated gap exactly where it was — no visual jump.
        int wrapperIndex = componentIndexOf(source.panel(), wrapper);
        draggedWrapper = wrapper;
        if (wrapper instanceof JComponent jc) {
            Dimension zero = new Dimension(0, 0);
            jc.setPreferredSize(zero);
            jc.setMinimumSize(zero);
            jc.setMaximumSize(zero);
        }
        wrapper.setVisible(false);
        gap = new AnimatedGap(source.vertical(), gapSize(source), true);
        gapStack = source;
        source.panel().add(gap, wrapperIndex);
        source.panel().revalidate();
        source.panel().repaint();
        layer.getGlassPane().setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
    }

    /** Moves the landing gap: the old one shrinks away while the new one grows open. */
    private void placeGap(@NotNull DropStack target, int slot) {
        if (gap != null && gapStack == target && slotOfGap(target) == slot) {
            return;
        }
        if (gap != null) {
            gap.shrinkAndRemove();
        }
        gap = new AnimatedGap(target.vertical(), gapSize(target), false);
        gapStack = target;
        target.panel().add(gap, componentIndexForSlot(target, slot));
        target.panel().revalidate();
        target.panel().repaint();
    }

    /** Persists every section's layout (buttons + order) and refreshes the preview. */
    private void commitDrop() {
        UiCustomizationService service = UiCustomizationService.getInstance();
        for (DropStack stack : stacks) {
            List<ButtonId> ids = new ArrayList<>();
            for (Component component : stack.panel().getComponents()) {
                if (component == draggedWrapper) {
                    continue; // The collapsed anchor — the gap marks the dragged button's spot.
                }
                if (component == gap && pressedId != null) {
                    ids.add(pressedId);
                    continue;
                }
                ButtonId id = UiEditSupport.wrapperId(component);
                if (id != null) {
                    ids.add(id);
                }
            }
            service.saveLayout(stack.group(), ids);
        }
        gap = null;
        gapStack = null;
        draggedWrapper = null;
        service.notifyCustomizationChanged();
    }

    private @NotNull Dimension gapSize(@NotNull DropStack stack) {
        Dimension size = draggedButtonSize == null ? new Dimension(60, 24) : draggedButtonSize;
        return stack.vertical()
                ? new Dimension(10, size.height + 4)
                : new Dimension(size.width + 4, size.height);
    }

    // ---------------------------------------------------------------- geometry

    private @NotNull Point pointInLayer(@NotNull MouseEvent e) {
        return SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), layer);
    }

    /** The tagged preview button under the point, if any. */
    private @Nullable JButton buttonAt(@NotNull Point pInLayer) {
        Component view = layer.getView();
        Point pv = SwingUtilities.convertPoint(layer, pInLayer, view);
        Component deepest = SwingUtilities.getDeepestComponentAt(view, pv.x, pv.y);
        for (Component c = deepest; c != null && c != view; c = c.getParent()) {
            if (c instanceof JButton button && UiEditSupport.buttonId(button) != null) {
                return button;
            }
        }
        return null;
    }

    /** The drop section under the point (bounds slightly expanded to be forgiving). */
    private @Nullable DropStack stackAt(@NotNull Point pInLayer) {
        for (DropStack stack : stacks) {
            if (!stack.panel().isShowing()) {
                continue;
            }
            Rectangle bounds = SwingUtilities.convertRectangle(
                    stack.panel().getParent(), stack.panel().getBounds(), layer);
            bounds.grow(8, 8);
            if (bounds.contains(pInLayer)) {
                return stack;
            }
        }
        return null;
    }

    private @Nullable DropStack stackOf(@Nullable Component wrapper) {
        if (wrapper == null) {
            return null;
        }
        for (DropStack stack : stacks) {
            if (stack.panel() == wrapper.getParent()) {
                return stack;
            }
        }
        return null;
    }

    // ------------------------------------- slot math (gaps and the collapsed anchor excluded)

    /** A stationary button wrapper that counts as a slot (not a gap, not the dragged one). */
    private boolean isSlot(@NotNull Component component) {
        return component != draggedWrapper && UiEditSupport.wrapperId(component) != null;
    }

    /** Where among the stack's buttons the drop would land, from the mouse position. */
    private int insertSlotAt(@NotNull DropStack stack, @NotNull Point pInLayer) {
        Point p = SwingUtilities.convertPoint(layer, pInLayer, stack.panel());
        int slot = 0;
        for (Component component : stack.panel().getComponents()) {
            if (!isSlot(component)) {
                continue;
            }
            int mid = stack.vertical()
                    ? component.getY() + component.getHeight() / 2
                    : component.getX() + component.getWidth() / 2;
            if ((stack.vertical() ? p.y : p.x) < mid) {
                return slot;
            }
            slot++;
        }
        return slot;
    }

    private int slotOfGap(@NotNull DropStack stack) {
        int slot = 0;
        for (Component component : stack.panel().getComponents()) {
            if (component == gap) {
                return slot;
            }
            if (isSlot(component)) {
                slot++;
            }
        }
        return -1;
    }

    /** Container index that places a component right before the given button slot. */
    private int componentIndexForSlot(@NotNull DropStack stack, int slot) {
        int seen = 0;
        Component[] components = stack.panel().getComponents();
        for (int i = 0; i < components.length; i++) {
            if (isSlot(components[i])) {
                if (seen == slot) {
                    return i;
                }
                seen++;
            }
        }
        return components.length;
    }

    private static int componentIndexOf(@NotNull JPanel panel, @NotNull Component child) {
        Component[] components = panel.getComponents();
        for (int i = 0; i < components.length; i++) {
            if (components[i] == child) {
                return i;
            }
        }
        return components.length;
    }

    private static @NotNull BufferedImage snapshot(@NotNull JComponent component) {
        int width = Math.max(1, component.getWidth());
        int height = Math.max(1, component.getHeight());
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        component.paint(g);
        g.dispose();
        return image;
    }

    // ---------------------------------------------------------------- gap animation

    /**
     * The animated landing slot: eases open when placed and eases shut when the
     * drag moves on, so the surrounding buttons glide instead of jumping.
     */
    private static final class AnimatedGap extends JComponent {
        private final boolean vertical;
        private final Dimension full;
        private final Timer timer;
        private long animationStart = System.currentTimeMillis();
        private float progress;
        private float dyingFrom;
        private boolean dying;

        AnimatedGap(boolean vertical, @NotNull Dimension full, boolean startOpen) {
            this.vertical = vertical;
            this.full = full;
            this.progress = startOpen ? 1f : 0f;
            this.timer = new Timer(15, e -> tick());
            if (!startOpen) {
                timer.start();
            }
        }

        void shrinkAndRemove() {
            dying = true;
            dyingFrom = progress;
            animationStart = System.currentTimeMillis();
            timer.restart();
        }

        private void tick() {
            float t = Math.min(1f, (System.currentTimeMillis() - animationStart) / (float) ANIMATION_MS);
            float eased = 1f - (1f - t) * (1f - t) * (1f - t);
            progress = dying ? dyingFrom * (1f - eased) : eased;
            revalidate();
            java.awt.Container parent = getParent();
            if (parent != null) {
                parent.revalidate();
                parent.repaint();
            }
            if (t >= 1f) {
                timer.stop();
                if (dying && parent != null) {
                    parent.remove(this);
                    parent.revalidate();
                    parent.repaint();
                }
            }
        }

        private @NotNull Dimension current() {
            return vertical
                    ? new Dimension(full.width, Math.round(full.height * progress))
                    : new Dimension(Math.round(full.width * progress), full.height);
        }

        @Override
        public Dimension getPreferredSize() {
            return current();
        }

        @Override
        public Dimension getMinimumSize() {
            return current();
        }

        @Override
        public Dimension getMaximumSize() {
            Dimension current = current();
            return vertical
                    ? new Dimension(Integer.MAX_VALUE, current.height)
                    : new Dimension(current.width, Integer.MAX_VALUE);
        }
    }
}

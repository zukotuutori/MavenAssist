package com.zukotuutori.mavenassist.ui;

import com.intellij.openapi.keymap.KeymapUtil;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JTextField;
import javax.swing.KeyStroke;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Click it, press a key combination, and it holds that combination. A read-only text field
 * rather than a real editor: the text is always a rendering of the recorded
 * {@link KeyStroke}, never something typed.
 *
 * <p>Everything happens locally. The recorded shortcut is handed to the IDE's keymap by
 * the dialog that owns this field.</p>
 */
final class ShortcutField extends JTextField {

    private static final String EMPTY_TEXT = "Click here, then press a shortcut";
    private static final String RECORDING_TEXT = "Press a shortcut…  (Esc to cancel)";

    private @Nullable KeyStroke keyStroke;
    private boolean recording;

    ShortcutField(@Nullable KeyStroke initial) {
        this.keyStroke = initial;
        setEditable(false);
        // Otherwise Tab would move focus instead of being recordable.
        setFocusTraversalKeysEnabled(false);
        setColumns(24);
        setMargin(JBUI.insets(2, 6));
        setToolTipText("Click and press the key combination that should run auto update. "
                + "It is stored in your IDE keymap, where you can also change it later.");

        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                startRecording();
            }
        });
        addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                stopRecording();
            }
        });
        render();
    }

    private void startRecording() {
        if (!isEnabled()) {
            return;
        }
        recording = true;
        requestFocusInWindow();
        render();
    }

    private void stopRecording() {
        recording = false;
        render();
    }

    /**
     * Takes the key event itself instead of using a listener, so the combination is caught
     * before any editor binding or focus traversal can act on it.
     */
    @Override
    protected void processKeyEvent(KeyEvent e) {
        if (!recording || e.getID() != KeyEvent.KEY_PRESSED) {
            // Swallow releases and typed events while recording so nothing leaks through.
            if (recording) {
                e.consume();
            }
            return;
        }
        e.consume();

        int code = e.getKeyCode();
        if (code == KeyEvent.VK_ESCAPE) {
            stopRecording();
            return;
        }
        // A modifier on its own is not a shortcut, so keep waiting for the real key.
        if (code == KeyEvent.VK_SHIFT || code == KeyEvent.VK_CONTROL || code == KeyEvent.VK_ALT
                || code == KeyEvent.VK_META || code == KeyEvent.VK_ALT_GRAPH || code == KeyEvent.VK_UNDEFINED) {
            return;
        }
        keyStroke = KeyStroke.getKeyStroke(code, e.getModifiersEx());
        recording = false;
        render();
    }

    /** The recorded combination, or null while none has been set. */
    @Nullable KeyStroke getKeyStroke() {
        return keyStroke;
    }

    void clear() {
        keyStroke = null;
        recording = false;
        render();
    }

    private void render() {
        if (recording) {
            setText(RECORDING_TEXT);
            setForeground(UIUtil.getContextHelpForeground());
        } else if (keyStroke == null) {
            setText(EMPTY_TEXT);
            setForeground(UIUtil.getContextHelpForeground());
        } else {
            setText(KeymapUtil.getKeystrokeText(keyStroke));
            setForeground(UIUtil.getLabelForeground());
        }
    }

    /** Human-readable form of a keystroke, for messages outside this field. */
    static @NotNull String textOf(@Nullable KeyStroke keyStroke) {
        return keyStroke == null ? "" : KeymapUtil.getKeystrokeText(keyStroke);
    }
}

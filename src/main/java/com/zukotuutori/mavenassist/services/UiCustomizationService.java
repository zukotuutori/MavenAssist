package com.zukotuutori.mavenassist.services;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import java.awt.Color;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists the user's UI customization of the plugin page: per-button text, colors,
 * position (order) and size, plus which dependency fields are shown directly in the
 * table and which only in the "Show Details" dialog. Applied everywhere via
 * {@link #applyTo(ButtonId, JButton)} and the visibility queries.
 */
@Service
@State(name = "MavenAssistUiCustomization", storages = @Storage("mavenAssistUiCustomization.xml"))
public final class UiCustomizationService implements PersistentStateComponent<UiCustomizationService.State> {

    /** Where a customizable button lives; reordering only moves buttons within their group. */
    public enum Group {
        DIRECTORY_BAR("Directory bar"),
        TABLE_ACTIONS("Dependency table buttons"),
        TABLE_ROW("Table row"),
        DETAILS_DIALOG("Details dialog");

        public final String displayName;

        Group(String displayName) {
            this.displayName = displayName;
        }
    }

    /** Every button on the plugin page that can be customized. */
    public enum ButtonId {
        // Retired: Browse is now a fixed folder-icon button beside the directory field,
        // Scan is gone (Enter in the field / choosing a directory rescans), the parent pom
        // button sits fixed next to the "Parent pom:" line, Check for Updates / Add
        // Dependencies are fixed at the bottom of the Dependencies box, and Add to Manager
        // lives inside the Add Dependencies dialog. The enum values stay so saved styles
        // from older versions still deserialize.
        BROWSE("Browse...", Group.DIRECTORY_BAR, true),
        SCAN("Scan", Group.DIRECTORY_BAR, true),
        ADD_PARENT("Add Parent Pom...", Group.TABLE_ACTIONS, true),
        CHECK_UPDATES("Check for Updates", Group.TABLE_ACTIONS, true),
        ADD_DEPENDENCIES("Add Dependencies", Group.TABLE_ACTIONS, true),
        ADD_TO_MANAGER("Add Dependencies to Manager", Group.TABLE_ACTIONS, true),
        // Retired with the whole "Actions" box: the table reloads automatically on save.
        RELOAD("Reload", Group.TABLE_ACTIONS, true),
        SHOW_DETAILS("Details", Group.TABLE_ROW),
        UPDATE_NEWEST("Update to Newest Version", Group.DETAILS_DIALOG),
        CHANGE_VERSION("Change Version...", Group.DETAILS_DIALOG);

        public final String defaultText;
        public final Group group;
        /** No longer shown or customizable anywhere. */
        public final boolean retired;

        ButtonId(String defaultText, Group group) {
            this(defaultText, group, false);
        }

        ButtonId(String defaultText, Group group, boolean retired) {
            this.defaultText = defaultText;
            this.group = group;
            this.retired = retired;
        }
    }

    /** Dependency fields whose visibility (table vs. details dialog) can be toggled. */
    public enum Field {
        NAME("Name", true, true),
        GROUP_ID("Group id", false, true),
        ARTIFACT_ID("Artifact id", false, true),
        VERSION("Version", true, true),
        LATEST_VERSION("Newest version", false, true),
        AUTO_UPDATE("Auto update", true, true);

        public final String displayName;
        public final boolean defaultInTable;
        public final boolean defaultInDetails;

        Field(String displayName, boolean defaultInTable, boolean defaultInDetails) {
            this.displayName = displayName;
            this.defaultInTable = defaultInTable;
            this.defaultInDetails = defaultInDetails;
        }
    }

    /** One button's saved customization. Empty string / 0 / -1 means "use the default". */
    public static class ButtonStyle {
        public String text = "";
        public String background = "";
        public String foreground = "";
        /** Name of the {@link Group} the button was moved to; empty = its home group. */
        public String container = "";
        public int order = -1;
        /** Fixed width in px (0 = stretch/default); a fixed-width button no longer fills its row. */
        public int width = 0;
        public int height = 0;
        public int fontSize = 0;

        public @NotNull ButtonStyle copy() {
            ButtonStyle c = new ButtonStyle();
            c.text = text;
            c.background = background;
            c.foreground = foreground;
            c.container = container;
            c.order = order;
            c.width = width;
            c.height = height;
            c.fontSize = fontSize;
            return c;
        }

        public boolean isDefault() {
            return text.isEmpty() && background.isEmpty() && foreground.isEmpty()
                    && container.isEmpty() && order < 0 && width <= 0 && height <= 0 && fontSize <= 0;
        }
    }

    public static class State {
        public Map<String, ButtonStyle> buttons = new HashMap<>();
        public Map<String, Boolean> fieldInTable = new HashMap<>();
        public Map<String, Boolean> fieldInDetails = new HashMap<>();
    }

    private State state = new State();

    /** True while the user is visually editing the plugin page. Not persisted. */
    private boolean editMode;
    private final List<Runnable> changeListeners = new ArrayList<>();

    public static UiCustomizationService getInstance() {
        return ApplicationManager.getApplication().getService(UiCustomizationService.class);
    }

    // ---------------------------------------------------------------- edit mode & live updates

    public boolean isEditMode() {
        return editMode;
    }

    public void setEditMode(boolean editMode) {
        this.editMode = editMode;
    }

    public void addChangeListener(@NotNull Runnable listener) {
        changeListeners.add(listener);
    }

    public void removeChangeListener(@NotNull Runnable listener) {
        changeListeners.remove(listener);
    }

    /** Tells every open UI to re-apply the current customization. Call on the EDT. */
    public void notifyCustomizationChanged() {
        for (Runnable listener : new ArrayList<>(changeListeners)) {
            listener.run();
        }
    }

    /** Deep copy of the current settings — taken when edit mode starts, restored on Cancel. */
    public @NotNull State copyCurrentState() {
        return copyOf(state);
    }

    public void restoreFrom(@NotNull State snapshot) {
        this.state = copyOf(snapshot);
    }

    private static @NotNull State copyOf(@NotNull State source) {
        State copy = new State();
        for (Map.Entry<String, ButtonStyle> entry : source.buttons.entrySet()) {
            copy.buttons.put(entry.getKey(), entry.getValue().copy());
        }
        copy.fieldInTable.putAll(source.fieldInTable);
        copy.fieldInDetails.putAll(source.fieldInDetails);
        return copy;
    }

    // ---------------------------------------------------------------- buttons

    /** Never returns null; a missing entry means "all defaults". */
    public @NotNull ButtonStyle getStyle(@NotNull ButtonId id) {
        ButtonStyle style = state.buttons.get(id.name());
        return style == null ? new ButtonStyle() : style;
    }

    public void setStyle(@NotNull ButtonId id, @NotNull ButtonStyle style) {
        if (style.isDefault()) {
            state.buttons.remove(id.name());
        } else {
            state.buttons.put(id.name(), style);
        }
    }

    public @NotNull String getButtonText(@NotNull ButtonId id) {
        String text = getStyle(id).text;
        return text.isEmpty() ? id.defaultText : text;
    }

    /** The section of the page a button currently lives in (buttons can be moved anywhere). */
    public @NotNull Group containerOf(@NotNull ButtonId id) {
        String container = getStyle(id).container;
        if (!container.isEmpty()) {
            try {
                Group group = Group.valueOf(container);
                // The "Actions" box no longer exists — buttons saved there go back home.
                return group == Group.TABLE_ACTIONS ? id.group : group;
            } catch (IllegalArgumentException ignored) {
                // Unknown value from an older/newer version — fall back to the home group.
            }
        }
        return id.group;
    }

    /** All buttons currently placed in the given section, in their saved order. */
    public @NotNull List<ButtonId> buttonsIn(@NotNull Group container) {
        List<ButtonId> ids = new ArrayList<>();
        for (ButtonId id : ButtonId.values()) {
            if (!id.retired && containerOf(id) == container) {
                ids.add(id);
            }
        }
        ids.sort(Comparator.comparingInt(id -> {
            int order = getStyle(id).order;
            return order >= 0 ? order : defaultIndexInGroup(id);
        }));
        return ids;
    }

    /** Persists a section's full drag-and-drop layout: which buttons it holds and their order. */
    public void saveLayout(@NotNull Group container, @NotNull List<ButtonId> ordered) {
        for (int i = 0; i < ordered.size(); i++) {
            ButtonId id = ordered.get(i);
            ButtonStyle style = getStyle(id);
            style.container = container == id.group ? "" : container.name();
            style.order = i;
            setStyle(id, style);
        }
    }

    /** A button's position within its group when nothing is customized. */
    public static int defaultIndexInGroup(@NotNull ButtonId id) {
        int index = 0;
        for (ButtonId other : ButtonId.values()) {
            if (other == id) {
                return index;
            }
            if (other.group == id.group) {
                index++;
            }
        }
        return index;
    }

    /** Client property set by {@link #applyTo}: true when the button has a fixed custom width. */
    public static final String FIXED_WIDTH_PROPERTY = "mavenAssist.fixedWidth";

    /** Restores a button's default look; use before re-applying a style to a reused button. */
    public static void resetVisuals(@NotNull JButton button) {
        JButton defaults = new JButton();
        button.setFont(defaults.getFont());
        button.setBackground(defaults.getBackground());
        button.setForeground(defaults.getForeground());
        button.setContentAreaFilled(true);
        button.setPreferredSize(null);
        button.putClientProperty("JButton.backgroundColor", null);
        button.putClientProperty(FIXED_WIDTH_PROPERTY, null);
    }

    /** Applies text, colors and size to the button. Call once right after creating it. */
    public void applyTo(@NotNull ButtonId id, @NotNull JButton button) {
        ButtonStyle style = getStyle(id);
        button.setText(getButtonText(id));
        Color background = parseColor(style.background);
        if (background != null) {
            // Painted inside the rounded button shape by the IntelliJ look-and-feel;
            // setOpaque(true) would also fill the square bounds behind the corners.
            button.setBackground(background);
            button.putClientProperty("JButton.backgroundColor", background);
        }
        Color foreground = parseColor(style.foreground);
        if (foreground != null) {
            button.setForeground(foreground);
        }
        if (style.fontSize > 0) {
            button.setFont(button.getFont().deriveFont((float) JBUI.scaleFontSize((float) style.fontSize)));
        }
        if (style.width > 0 || style.height > 0) {
            Dimension preferred = button.getPreferredSize();
            button.setPreferredSize(new Dimension(
                    style.width > 0 ? JBUI.scale(style.width) : preferred.width,
                    style.height > 0 ? JBUI.scale(style.height) : preferred.height));
        }
        button.putClientProperty(FIXED_WIDTH_PROPERTY, style.width > 0);
    }

    // ---------------------------------------------------------------- field visibility

    public boolean isShownInTable(@NotNull Field field) {
        return state.fieldInTable.getOrDefault(field.name(), field.defaultInTable);
    }

    public boolean isShownInDetails(@NotNull Field field) {
        return state.fieldInDetails.getOrDefault(field.name(), field.defaultInDetails);
    }

    public void setShownInTable(@NotNull Field field, boolean shown) {
        state.fieldInTable.put(field.name(), shown);
    }

    public void setShownInDetails(@NotNull Field field, boolean shown) {
        state.fieldInDetails.put(field.name(), shown);
    }

    /** The table's visible dependency fields, in their canonical column order. */
    public @NotNull List<Field> visibleTableFields() {
        List<Field> visible = new ArrayList<>();
        for (Field field : Field.values()) {
            if (isShownInTable(field)) {
                visible.add(field);
            }
        }
        return visible;
    }

    public void resetToDefaults() {
        state.buttons.clear();
        state.fieldInTable.clear();
        state.fieldInDetails.clear();
    }

    // ---------------------------------------------------------------- colors

    /** Parses a hex color like "2E8B44"; null for empty/invalid. */
    public static @Nullable Color parseColor(@Nullable String hex) {
        if (hex == null || hex.isEmpty()) {
            return null;
        }
        try {
            return new Color(Integer.parseInt(hex, 16));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static @NotNull String toHex(@Nullable Color color) {
        return color == null ? "" : String.format("%06X", color.getRGB() & 0xFFFFFF);
    }

    @Override
    public @Nullable State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = state;
        if (this.state.buttons == null) {
            this.state.buttons = new HashMap<>();
        }
        if (this.state.fieldInTable == null) {
            this.state.fieldInTable = new HashMap<>();
        }
        if (this.state.fieldInDetails == null) {
            this.state.fieldInDetails = new HashMap<>();
        }
    }
}

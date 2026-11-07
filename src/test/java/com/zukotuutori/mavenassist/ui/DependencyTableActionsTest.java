package com.zukotuutori.mavenassist.ui;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.table.JBTable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.MenuElement;
import java.awt.Component;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Drives the real dependency table with real mouse events: the "⋯" column must appear on the
 * hovered row, a left-click on it and a right-click anywhere on a row must produce the action
 * menu, and a multi-selection must produce the bulk menu.
 */
public class DependencyTableActionsTest extends BasePlatformTestCase {

    private static final String POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>my-app</artifactId>
                <version>1.0.0</version>
                <dependencies>
                    <dependency>
                        <groupId>commons-io</groupId>
                        <artifactId>commons-io</artifactId>
                        <version>2.15.1</version>
                    </dependency>
                    <dependency>
                        <groupId>com.google.guava</groupId>
                        <artifactId>guava</artifactId>
                        <version>33.0.0-jre</version>
                    </dependency>
                    <dependency>
                        <groupId>org.slf4j</groupId>
                        <artifactId>slf4j-api</artifactId>
                        <version>2.0.9</version>
                    </dependency>
                </dependencies>
            </project>
            """;

    private CapturingPanel panel;
    private JBTable table;

    /** Records the menu instead of showing it: a popup needs a display, tests have none. */
    private static class CapturingPanel extends DependencyTablePanel {
        private @Nullable JPopupMenu shown;

        CapturingPanel(@NotNull com.intellij.openapi.project.Project project) {
            super(project);
        }

        @Override
        void showMenu(@NotNull JPopupMenu menu, int x, int y) {
            shown = menu;
        }
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        Path dir = Files.createTempDirectory("maven-assist-table-test");
        File pom = dir.resolve("pom.xml").toFile();
        Files.writeString(pom.toPath(), POM);

        panel = new CapturingPanel(getProject());
        panel.setPomFile(pom);
        table = panel.tableForTesting();
        // No window, so give the table a size and let it lay its columns out.
        table.setSize(1400, 400);
        table.doLayout();
    }

    public void testTableHasThreeRowsAndAnActionsColumn() {
        assertEquals(3, table.getRowCount());
        // Fields + Update + Details + Delete + the new "⋯" column.
        assertTrue("actions column missing", table.getColumnCount() >= 5);
        assertTrue("actions column has no width",
                table.getColumnModel().getColumn(table.getColumnCount() - 1).getWidth() > 0);
    }

    public void testHoverShowsTheHandleOnlyOnThatRow() {
        int column = table.getColumnCount() - 1;
        Rectangle cell = table.getCellRect(1, column, true);
        dispatch(MouseEvent.MOUSE_MOVED, cell.x + cell.width / 2, cell.y + cell.height / 2,
                MouseEvent.NOBUTTON, 0, false);

        assertNotNull("hovered row shows no handle", iconAt(1, column));
        assertNull("other rows must stay empty", iconAt(0, column));
        assertNull("other rows must stay empty", iconAt(2, column));
    }

    public void testLeftClickOnTheHandleOpensTheMenu() {
        int column = table.getColumnCount() - 1;
        Rectangle cell = table.getCellRect(2, column, true);
        dispatch(MouseEvent.MOUSE_CLICKED, cell.x + cell.width / 2, cell.y + cell.height / 2,
                MouseEvent.BUTTON1, InputEvent.BUTTON1_DOWN_MASK, false);

        assertNotNull("no menu after clicking the handle", panel.shown);
        assertEquals(2, table.getSelectedRow());
        assertTrue(labels(panel.shown).contains("Delete from pom..."));
    }

    public void testRightClickAnywhereOnARowOpensTheSingleRowMenu() {
        Rectangle cell = table.getCellRect(0, 0, true);
        rightClick(cell.x + 5, cell.y + cell.height / 2);

        assertNotNull("no menu after right-clicking a row", panel.shown);
        List<String> labels = labels(panel.shown);
        assertTrue(labels.contains("Details..."));
        assertTrue(labels.contains("Update to newest version"));
        assertTrue(labels.contains("Show all available versions..."));
        assertTrue(labels.contains("Custom version..."));
        assertTrue(labels.contains("Auto update"));
        assertTrue(labels.contains("Delete from pom..."));
    }

    public void testRightClickKeepsAnExistingMultiSelectionAndOffersBulkActions() {
        table.setRowSelectionInterval(0, 2);
        Rectangle cell = table.getCellRect(1, 0, true);
        rightClick(cell.x + 5, cell.y + cell.height / 2);

        assertNotNull("no menu for a multi-selection", panel.shown);
        assertEquals("the selection must survive the right-click", 3, table.getSelectedRowCount());
        List<String> labels = labels(panel.shown);
        assertTrue(labels.contains("3 dependencies selected"));
        assertTrue(labels.contains("Update 3 dependencies to the newest version"));
        assertTrue(labels.contains("Turn auto update on for 3 dependencies"));
        assertTrue(labels.contains("Turn auto update off for 3 dependencies"));
        assertTrue(labels.contains("Delete 3 dependencies from pom..."));
        assertFalse("a custom version makes no sense for several artifacts",
                labels.contains("Custom version..."));
    }

    public void testRightClickOutsideTheSelectionMovesItToThatRow() {
        table.setRowSelectionInterval(0, 0);
        Rectangle cell = table.getCellRect(2, 0, true);
        rightClick(cell.x + 5, cell.y + cell.height / 2);

        assertEquals(2, table.getSelectedRow());
        assertEquals(1, table.getSelectedRowCount());
        assertNotNull(panel.shown);
        assertTrue(labels(panel.shown).contains("Details..."));
    }

    /** The tool window is docked on the right and is narrow; the handle must survive that. */
    public void testHandleStaysReachableInANarrowToolWindow() {
        table.setSize(340, 400);
        table.doLayout();

        int column = table.getColumnCount() - 1;
        Rectangle cell = table.getCellRect(0, column, true);
        assertTrue("actions column squeezed away: width " + cell.width, cell.width >= 10);
        assertTrue("actions column pushed out of view: ends at " + (cell.x + cell.width)
                + " of " + table.getWidth(), cell.x + cell.width <= table.getWidth());

        dispatch(MouseEvent.MOUSE_CLICKED, cell.x + cell.width / 2, cell.y + cell.height / 2,
                MouseEvent.BUTTON1, InputEvent.BUTTON1_DOWN_MASK, false);
        assertNotNull("no menu in a narrow table", panel.shown);
    }

    /** Hiding or showing columns rebuilds the structure; the handle must come back with it. */
    public void testHandleSurvivesAColumnRebuild() {
        panel.applyCustomization();
        table.setSize(1400, 400);
        table.doLayout();

        int column = table.getColumnCount() - 1;
        Rectangle cell = table.getCellRect(1, column, true);
        dispatch(MouseEvent.MOUSE_MOVED, cell.x + cell.width / 2, cell.y + cell.height / 2,
                MouseEvent.NOBUTTON, 0, false);
        assertNotNull("handle lost after a structure change", iconAt(1, column));

        dispatch(MouseEvent.MOUSE_CLICKED, cell.x + cell.width / 2, cell.y + cell.height / 2,
                MouseEvent.BUTTON1, InputEvent.BUTTON1_DOWN_MASK, false);
        assertNotNull("no menu after a structure change", panel.shown);
    }

    public void testRightClickBelowTheLastRowOpensNothing() {
        rightClick(10, table.getRowHeight() * 10 + 5);
        assertNull(panel.shown);
    }

    // ---------------------------------------------------------------- helpers

    private void rightClick(int x, int y) {
        // Platforms differ in which of the two events carries the popup trigger; send both.
        dispatch(MouseEvent.MOUSE_PRESSED, x, y, MouseEvent.BUTTON3,
                InputEvent.BUTTON3_DOWN_MASK, true);
        dispatch(MouseEvent.MOUSE_RELEASED, x, y, MouseEvent.BUTTON3, 0, true);
    }

    private void dispatch(int id, int x, int y, int button, int modifiers, boolean popupTrigger) {
        table.dispatchEvent(new MouseEvent(table, id, System.currentTimeMillis(), modifiers,
                x, y, 1, popupTrigger, button));
    }

    /** The icon the "⋯" renderer draws for a cell, or null when it draws none. */
    private @Nullable javax.swing.Icon iconAt(int row, int column) {
        Component component = table.prepareRenderer(table.getCellRenderer(row, column), row, column);
        return component instanceof javax.swing.JLabel label ? label.getIcon() : null;
    }

    private static @NotNull List<String> labels(@Nullable JPopupMenu menu) {
        List<String> labels = new ArrayList<>();
        if (menu == null) {
            return labels;
        }
        for (MenuElement element : menu.getSubElements()) {
            if (element instanceof JMenuItem menuItem) {
                labels.add(menuItem.getText());
            }
        }
        return labels;
    }
}

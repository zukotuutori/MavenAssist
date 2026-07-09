package com.zukotuutori.mavenassist.ui;

import com.zukotuutori.mavenassist.services.UiCustomizationService;
import com.zukotuutori.mavenassist.services.UiCustomizationService.ButtonId;
import com.zukotuutori.mavenassist.util.Notifier;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.fileEditor.FileDocumentManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.io.File;
import java.util.List;

/**
 * Main tool window panel: a directory chooser on top, and below it either the
 * "create pom" view (no pom.xml in the directory) or the dependency table view.
 * Reloads automatically when the project is saved or the pom changes on disk,
 * and then auto-updates all dependencies flagged "auto update".
 */
public class MavenAssistPanel extends JPanel implements Disposable {

    private static final String CARD_CREATE = "create";
    private static final String CARD_TABLE = "table";
    private static final String CARD_EMPTY = "empty";

    private final Project project;
    private final JTextField directoryField = new JTextField();
    private final CardLayout cards = new CardLayout();
    private final JPanel cardPanel = new JPanel(cards);
    private final CreatePomPanel createPanel;
    private final DependencyTablePanel tablePanel;
    private final JPanel directoryButtonsRow = new JPanel();
    private final Runnable customizationListener = this::onCustomizationChanged;

    private String activeCard = CARD_EMPTY;

    public MavenAssistPanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;
        this.createPanel = new CreatePomPanel(project, this::refresh);
        this.tablePanel = new DependencyTablePanel(project);

        // One shared action map: buttons can be moved into any section and still work.
        java.util.Map<ButtonId, Runnable> actions = new java.util.EnumMap<>(ButtonId.class);
        tablePanel.registerSharedActions(actions);

        add(buildDirectoryBar(), BorderLayout.NORTH);

        UiCustomizationService.getInstance().addChangeListener(customizationListener);

        cardPanel.add(buildEmptyCard(), CARD_EMPTY);
        cardPanel.add(createPanel, CARD_CREATE);
        cardPanel.add(tablePanel, CARD_TABLE);
        add(cardPanel, BorderLayout.CENTER);
        add(buildBottomBar(), BorderLayout.SOUTH);

        String basePath = project.getBasePath();
        if (basePath != null) {
            directoryField.setText(basePath);
        }
        subscribeToSaveAndFileEvents();
        refresh();
    }

    /**
     * Reload on project save (Save All / auto-save) and whenever the watched pom.xml
     * changes on disk — both followed by an auto-update pass for flagged dependencies.
     */
    private void subscribeToSaveAndFileEvents() {
        ApplicationManager.getApplication().getMessageBus().connect(this)
                .subscribe(FileDocumentManagerListener.TOPIC, new FileDocumentManagerListener() {
                    @Override
                    public void beforeAllDocumentsSaving() {
                        // invokeLater: run after the documents have actually been written to disk.
                        ApplicationManager.getApplication().invokeLater(() -> onProjectSaved());
                    }
                });

        project.getMessageBus().connect(this)
                .subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
                    @Override
                    public void after(@NotNull List<? extends VFileEvent> events) {
                        String pomPath = watchedPomPath();
                        if (pomPath == null) {
                            return;
                        }
                        for (VFileEvent event : events) {
                            if (pomPath.equals(event.getPath())) {
                                ApplicationManager.getApplication().invokeLater(() -> onProjectSaved());
                                return;
                            }
                        }
                    }
                });
    }

    private void onProjectSaved() {
        refresh();
        if (CARD_TABLE.equals(activeCard)) {
            tablePanel.runAutoUpdate();
        }
    }

    private @Nullable String watchedPomPath() {
        String text = directoryField.getText().trim();
        if (text.isEmpty()) {
            return null;
        }
        return FileUtil.toSystemIndependentName(new File(text, "pom.xml").getAbsolutePath());
    }

    private @NotNull JPanel buildDirectoryBar() {
        // Field on top, buttons below (only shown when the user placed buttons up here).
        JPanel inner = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        inner.setOpaque(false);

        JPanel fieldRow = new JPanel(new BorderLayout(JBUI.scale(4), 0));
        fieldRow.setOpaque(false);
        fieldRow.add(directoryField, BorderLayout.CENTER);
        JButton browseButton = new JButton(AllIcons.Nodes.Folder);
        browseButton.setToolTipText("Choose project directory");
        browseButton.setMargin(JBUI.insets(2, 6));
        browseButton.addActionListener(e -> browseDirectory());
        fieldRow.add(browseButton, BorderLayout.EAST);
        inner.add(fieldRow, BorderLayout.NORTH);

        directoryButtonsRow.setLayout(new javax.swing.BoxLayout(directoryButtonsRow, javax.swing.BoxLayout.X_AXIS));
        directoryButtonsRow.setOpaque(false);
        rebuildDirectoryButtons();
        inner.add(directoryButtonsRow, BorderLayout.CENTER);

        directoryField.addActionListener(e -> refresh());

        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(JBUI.Borders.empty(6, 6, 0, 6));
        bar.add(new SectionBox("Directory", AllIcons.Nodes.Folder, inner), BorderLayout.CENTER);
        return bar;
    }

    /** Bottom bar: the blue "Customize..." link on the left, "MVN Clean Install" on the right. */
    private @NotNull JPanel buildBottomBar() {
        ActionLink customizeLink = new ActionLink("Customize...",
                (java.awt.event.ActionListener) e -> new CustomizePageDialog(project).show());
        customizeLink.setToolTipText("Opens the customize window: drag buttons to move them, click a "
                + "button to change its text, colors and size — applied here when you press Save.");

        JButton cleanInstallButton = new JButton("MVN Clean Install",
                com.intellij.util.IconUtil.colorize(AllIcons.Actions.Execute, java.awt.Color.WHITE));
        cleanInstallButton.setToolTipText("Runs \"mvn clean install\" in the chosen directory");
        cleanInstallButton.setMargin(JBUI.insets(2, 8));
        // IntelliJ blue, painted inside the rounded button shape; white text on top.
        cleanInstallButton.putClientProperty("JButton.backgroundColor",
                new com.intellij.ui.JBColor(new java.awt.Color(0x3574F0), new java.awt.Color(0x3574F0)));
        cleanInstallButton.setForeground(java.awt.Color.WHITE);
        cleanInstallButton.addActionListener(e -> runCleanInstall());

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBorder(JBUI.Borders.empty(2, 8, 4, 8));
        bottom.add(customizeLink, BorderLayout.WEST);
        bottom.add(cleanInstallButton, BorderLayout.EAST);
        return bottom;
    }

    /** Runs "mvn clean install" in the chosen directory (wrapper preferred, like all builds). */
    private void runCleanInstall() {
        String text = directoryField.getText().trim();
        File dir = text.isEmpty() ? null : new File(text);
        if (dir == null || !dir.isDirectory()) {
            Notifier.warn(project, "Choose a valid project directory first.");
            return;
        }
        if (!new File(dir, "pom.xml").isFile()) {
            Notifier.warn(project, "No pom.xml in " + dir.getAbsolutePath() + " — nothing to build.");
            return;
        }
        if (com.zukotuutori.mavenassist.maven.MavenBuildRunner.findMavenExecutable(dir) == null) {
            offerMavenInstall(dir);
            return;
        }
        com.zukotuutori.mavenassist.maven.MavenBuildRunner.run(project, dir,
                new String[]{"clean", "install"}, null);
    }

    /** Maven is missing: ask where to install it (project vs. whole PC), install, then build. */
    private void offerMavenInstall(@NotNull File dir) {
        int answer = com.intellij.openapi.ui.Messages.showYesNoCancelDialog(project,
                "Maven (mvn) was not found on this system. Where should it be installed?\n\n"
                        + "• In this project: downloaded from apache.org into "
                        + new File(dir, ".mavenassist").getAbsolutePath() + " — used for this project only.\n"
                        + "• On this PC: via Homebrew/winget if available, otherwise downloaded "
                        + "to ~/.mavenassist — used for all projects.\n\n"
                        + "\"mvn clean install\" runs automatically afterwards.",
                "Install Maven", "In This Project", "On This PC", "Cancel",
                com.intellij.openapi.ui.Messages.getQuestionIcon());
        com.zukotuutori.mavenassist.maven.MavenBuildRunner.InstallScope scope;
        if (answer == com.intellij.openapi.ui.Messages.YES) {
            scope = com.zukotuutori.mavenassist.maven.MavenBuildRunner.InstallScope.PROJECT;
        } else if (answer == com.intellij.openapi.ui.Messages.NO) {
            scope = com.zukotuutori.mavenassist.maven.MavenBuildRunner.InstallScope.MACHINE;
        } else {
            return;
        }
        com.zukotuutori.mavenassist.maven.MavenBuildRunner.installMaven(project, scope, dir,
                () -> com.zukotuutori.mavenassist.maven.MavenBuildRunner.run(project, dir,
                        new String[]{"clean", "install"}, null));
    }

    /** Whatever buttons the user placed in the top bar; hidden entirely when there are none. */
    private void rebuildDirectoryButtons() {
        UiCustomizationService service = UiCustomizationService.getInstance();
        directoryButtonsRow.removeAll();
        for (ButtonId id : service.buttonsIn(UiCustomizationService.Group.DIRECTORY_BAR)) {
            Runnable action = tablePanel.getSharedActions().get(id);
            if (action == null) {
                continue;
            }
            JButton button = new JButton();
            service.applyTo(id, button);
            button.addActionListener(e -> action.run());
            directoryButtonsRow.add(UiEditSupport.wrap(button, false));
        }
        directoryButtonsRow.setVisible(directoryButtonsRow.getComponentCount() > 0);
        directoryButtonsRow.revalidate();
        directoryButtonsRow.repaint();
    }

    /**
     * Refreshes this page when the customize window finishes (Save or Cancel).
     * While that window is open (edit mode), its live preview changes are ignored here.
     */
    private void onCustomizationChanged() {
        if (UiCustomizationService.getInstance().isEditMode()) {
            return;
        }
        rebuildDirectoryButtons();
        tablePanel.applyCustomization();
    }

    private @NotNull JPanel buildEmptyCard() {
        JPanel panel = new JPanel(new BorderLayout());
        JBLabel label = new JBLabel("Choose a valid project directory above (folder button or press Enter).");
        label.setHorizontalAlignment(JBLabel.CENTER);
        panel.add(label, BorderLayout.CENTER);
        return panel;
    }

    private void browseDirectory() {
        FileChooserDescriptor descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
                .withTitle("Choose Project Directory");
        VirtualFile toSelect = null;
        File current = new File(directoryField.getText().trim());
        if (current.isDirectory()) {
            toSelect = LocalFileSystem.getInstance().findFileByIoFile(current);
        }
        VirtualFile chosen = FileChooser.chooseFile(descriptor, project, toSelect);
        if (chosen != null) {
            directoryField.setText(chosen.getPath());
            refresh();
        }
    }

    /** Re-detects whether a pom.xml exists in the chosen directory and switches the view accordingly. */
    public void refresh() {
        String text = directoryField.getText().trim();
        File dir = text.isEmpty() ? null : new File(text);
        if (dir == null || !dir.isDirectory()) {
            showCard(CARD_EMPTY);
            if (dir != null) {
                Notifier.warn(project, "'" + text + "' is not a directory.");
            }
            return;
        }
        File pom = new File(dir, "pom.xml");
        if (pom.isFile()) {
            tablePanel.setPomFile(pom);
            showCard(CARD_TABLE);
        } else {
            createPanel.setTargetDirectory(dir);
            showCard(CARD_CREATE);
        }
    }

    private void showCard(@NotNull String card) {
        activeCard = card;
        cards.show(cardPanel, card);
    }

    @Override
    public void dispose() {
        // Message bus connections registered with this Disposable are released automatically.
        UiCustomizationService.getInstance().removeChangeListener(customizationListener);
    }
}

package com.zukotuutori.mavenassist.ui;

import com.zukotuutori.mavenassist.maven.PomUtils;
import com.zukotuutori.mavenassist.services.AutoUpdateRunner;
import com.zukotuutori.mavenassist.services.AutoUpdateSettings;
import com.zukotuutori.mavenassist.services.MavenAssistProjectSettings;
import com.zukotuutori.mavenassist.services.PomTemplateService;
import com.zukotuutori.mavenassist.services.UiCustomizationService;
import com.zukotuutori.mavenassist.util.Notifier;
import com.zukotuutori.mavenassist.util.ProjectTrust;
import com.intellij.icons.AllIcons;
import com.intellij.ide.ui.LafManagerListener;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ex.AnActionListener;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.fileEditor.FileDocumentManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.IJSwingUtilities;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private static final String SAVE_ALL_ACTION_ID = "SaveAll";
    private static final String SAVE_DOCUMENT_ACTION_ID = "SaveDocument";

    private final Project project;
    private final JTextField directoryField = new JTextField();
    private final CardLayout cards = new CardLayout();
    private final JPanel cardPanel = new JPanel(cards);
    private final CreatePomPanel createPanel;
    private final DependencyTablePanel tablePanel;
    private final Runnable customizationListener = this::onCustomizationChanged;
    private final AtomicBoolean saveCallbackQueued = new AtomicBoolean();

    private String activeCard = CARD_EMPTY;

    public MavenAssistPanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;
        this.createPanel = new CreatePomPanel(project, this::refresh);
        this.tablePanel = new DependencyTablePanel(project);

        Theme.applyWindowBackground(this);
        Theme.applyWindowBackground(cardPanel);

        add(buildDirectoryBar(), BorderLayout.NORTH);

        UiCustomizationService.getInstance().addChangeListener(customizationListener);
        subscribeToThemeChanges();

        cardPanel.add(buildEmptyCard(), CARD_EMPTY);
        cardPanel.add(createPanel, CARD_CREATE);
        cardPanel.add(tablePanel, CARD_TABLE);
        add(cardPanel, BorderLayout.CENTER);
        add(buildBottomBar(), BorderLayout.SOUTH);

        File savedDirectory = MavenAssistProjectSettings.directory(project);
        if (savedDirectory != null) {
            directoryField.setText(savedDirectory.getAbsolutePath());
        }
        subscribeToSaveAndFileEvents();
        refresh();
    }

    /**
     * Switching the IDE theme (or toggling light/dark) must recolor the whole page, including
     * the cards it paints itself. The colors themselves come from {@link Theme} and re-read the
     * look-and-feel on every paint; this just re-installs the Swing UI delegates and repaints.
     */
    private void subscribeToThemeChanges() {
        ApplicationManager.getApplication().getMessageBus().connect(this)
                .subscribe(LafManagerListener.TOPIC, (LafManagerListener) source -> {
                    IJSwingUtilities.updateComponentTreeUI(this);
                    revalidate();
                    repaint();
                });
    }

    /**
     * Reload on project save (Save / Save All / auto-save) and whenever the watched pom.xml
     * changes on disk. Explicit save actions trigger even when there is no dirty document;
     * external/VFS changes merely refresh the table.
     */
    private void subscribeToSaveAndFileEvents() {
        ApplicationManager.getApplication().getMessageBus().connect(this)
                .subscribe(FileDocumentManagerListener.TOPIC, new FileDocumentManagerListener() {
                    @Override
                    public void beforeDocumentSaving(@NotNull Document document) {
                        VirtualFile file = com.intellij.openapi.fileEditor.FileDocumentManager
                                .getInstance().getFile(document);
                        if (file == null || !belongsToThisProject(file)) {
                            return;
                        }
                        queueProjectSavedCallback();
                    }
                });

        ApplicationManager.getApplication().getMessageBus().connect(this)
                .subscribe(AnActionListener.TOPIC, new AnActionListener() {
                    @Override
                    public void beforeActionPerformed(@NotNull AnAction action,
                                                      @NotNull AnActionEvent event) {
                        String actionId = ActionManager.getInstance().getId(action);
                        if (project.equals(event.getProject())
                                && (SAVE_ALL_ACTION_ID.equals(actionId)
                                || SAVE_DOCUMENT_ACTION_ID.equals(actionId))) {
                            queueProjectSavedCallback();
                        }
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
                                ApplicationManager.getApplication().invokeLater(() -> refresh());
                                return;
                            }
                        }
                    }
                });
    }

    /** Coalesces Save action and document-save events, then runs after disk writes complete. */
    private void queueProjectSavedCallback() {
        if (!saveCallbackQueued.compareAndSet(false, true)) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            saveCallbackQueued.set(false);
            if (!project.isDisposed()) {
                onProjectSaved();
            }
        });
    }

    private boolean belongsToThisProject(@NotNull VirtualFile file) {
        if (ProjectFileIndex.getInstance(project).isInContent(file)) {
            return true;
        }
        String selected = directoryField.getText().trim();
        if (selected.isEmpty()) {
            return false;
        }
        String directoryPath = FileUtil.toSystemIndependentName(
                new File(selected).toPath().toAbsolutePath().normalize().toString());
        String filePath = FileUtil.toSystemIndependentName(file.getPath());
        return filePath.equals(directoryPath) || filePath.startsWith(directoryPath + "/");
    }

    private void onProjectSaved() {
        refresh();
        if (CARD_TABLE.equals(activeCard)) {
            tablePanel.runAutoUpdate(AutoUpdateSettings.Trigger.ON_SAVE);
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

        directoryField.addActionListener(e -> refresh());

        JPanel bar = new JPanel(new BorderLayout());
        bar.setOpaque(false);
        bar.setBorder(JBUI.Borders.empty(6, 6, 0, 6));
        bar.add(new SectionBox("Directory", AllIcons.Nodes.Folder, inner), BorderLayout.CENTER);
        return bar;
    }

    /** Bottom bar shared by every view: customization, template actions and full Maven build. */
    private @NotNull JPanel buildBottomBar() {
        ActionLink customizeLink = new ActionLink("Customize...",
                (java.awt.event.ActionListener) e -> new CustomizePageDialog(project).show());
        customizeLink.setToolTipText("Choose dependency fields, auto-update triggers, "
                + "confirmation behavior and the keyboard shortcut.");

        JButton templateButton = new JButton("Template");
        templateButton.setToolTipText("Save, export or import Maven Assist templates");
        templateButton.setMargin(JBUI.insets(2, 8));
        templateButton.addActionListener(e -> showTemplateMenu(templateButton));

        JButton cleanInstallButton = new JButton("MVN Clean Install",
                com.intellij.util.IconUtil.colorize(AllIcons.Actions.Execute, java.awt.Color.WHITE));
        cleanInstallButton.setToolTipText("Runs \"mvn clean install\" in the chosen directory");
        cleanInstallButton.setMargin(JBUI.insets(2, 8));
        // The theme's own primary-button fill, so this reads as the IDE's accent color.
        Theme.applyAccent(cleanInstallButton);
        cleanInstallButton.addActionListener(e -> runCleanInstall());

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setOpaque(false);
        bottom.setBorder(JBUI.Borders.empty(2, 8, 4, 8));
        bottom.add(customizeLink, BorderLayout.WEST);
        JPanel actions = new JPanel(new java.awt.GridLayout(1, 2, JBUI.scale(4), 0));
        actions.setOpaque(false);
        actions.add(templateButton);
        actions.add(cleanInstallButton);
        bottom.add(actions, BorderLayout.EAST);
        return bottom;
    }

    private void showTemplateMenu(@NotNull JButton owner) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem saveAs = new JMenuItem("Save As...");
        saveAs.addActionListener(e -> saveAsTemplate());
        menu.add(saveAs);
        JMenuItem export = new JMenuItem("Export...");
        export.addActionListener(e -> exportCurrentPom());
        menu.add(export);
        JMenuItem importItem = new JMenuItem("Import...");
        importItem.addActionListener(e -> importTemplate());
        menu.add(importItem);
        menu.show(owner, 0, owner.getHeight());
    }

    /** Asks only for a name, then snapshots whichever of the create/manage views is active. */
    private void saveAsTemplate() {
        if (!CARD_CREATE.equals(activeCard) && !CARD_TABLE.equals(activeCard)) {
            Notifier.warn(project, "Choose a valid project directory first.");
            return;
        }
        String name = askTemplateName("Save as Template", "");
        if (name == null) {
            return;
        }
        try {
            PomTemplateService.Template template;
            if (CARD_CREATE.equals(activeCard)) {
                template = createPanel.snapshotTemplate(name);
            } else {
                String selected = directoryField.getText().trim();
                File pom = new File(selected, "pom.xml");
                template = PomTemplateService.snapshotPom(name, pom);
            }
            PomTemplateService.getInstance().save(template);
            Notifier.info(project, "Saved template \"" + name + "\".");
        } catch (java.io.IOException e) {
            Notifier.error(project, "Could not save the current pom as a template: " + e.getMessage());
        }
    }

    /** Copies the current project's pom.xml exactly into a user-selected folder. */
    private void exportCurrentPom() {
        String selected = directoryField.getText().trim();
        File source = selected.isEmpty() ? null : new File(selected, "pom.xml");
        if (source == null || !source.isFile()) {
            Notifier.warn(project, "There is no current pom.xml to export. "
                    + "Choose a project containing one or create it first.");
            return;
        }
        FileChooserDescriptor descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
                .withTitle("Export Current pom.xml")
                .withDescription("Choose the folder where the current pom.xml should be copied.");
        VirtualFile folder = FileChooser.chooseFile(descriptor, project, currentDirectoryVirtualFile());
        if (folder == null) {
            return;
        }
        File target = new File(folder.getPath(), "pom.xml");
        try {
            if (target.exists() && Files.isSameFile(source.toPath(), target.toPath())) {
                Notifier.warn(project, "The selected folder already contains the current pom.xml.");
                return;
            }
            if (target.exists()) {
                int answer = Messages.showYesNoDialog(project,
                        "pom.xml already exists in the selected folder.\n\nReplace it?",
                        "Replace Existing pom.xml", "Replace", "Cancel", Messages.getWarningIcon());
                if (answer != Messages.YES) {
                    return;
                }
            }
            PomUtils.copyPom(source, target);
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(target);
            Notifier.info(project, "Exported the current pom.xml to " + target.getAbsolutePath() + ".");
        } catch (IOException e) {
            Notifier.error(project, "Could not export the current pom.xml: " + e.getMessage());
        }
    }

    /** Imports a local pom.xml into the application-level template list. */
    private void importTemplate() {
        FileChooserDescriptor descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("xml")
                .withTitle("Import Template from pom.xml")
                .withDescription("Choose a pom.xml file up to 5 MB.");
        VirtualFile chosen = FileChooser.chooseFile(descriptor, project, currentDirectoryVirtualFile());
        if (chosen == null) {
            return;
        }
        try {
            PomTemplateService.Template template =
                    PomTemplateService.importPom("", new File(chosen.getPath()));
            String name = askTemplateName("Import Template", template.artifactId);
            if (name == null) {
                return;
            }
            template.name = name;
            PomTemplateService.getInstance().save(template);
            Notifier.info(project, "Imported pom.xml as template \"" + name + "\".");
        } catch (java.io.IOException e) {
            Notifier.error(project, "Could not import the selected pom.xml: " + e.getMessage());
        }
    }

    private @Nullable VirtualFile currentDirectoryVirtualFile() {
        String selected = directoryField.getText().trim();
        File directory = selected.isEmpty() ? null : new File(selected);
        return directory != null && directory.isDirectory()
                ? LocalFileSystem.getInstance().refreshAndFindFileByIoFile(directory)
                : null;
    }

    /** Keeps the interaction to one value while matching the existing unique-name rule. */
    private @Nullable String askTemplateName(@NotNull String title, @NotNull String initialValue) {
        while (true) {
            String input = Messages.showInputDialog(this,
                    "Template name:", title,
                    Messages.getQuestionIcon(), initialValue, null);
            if (input == null) {
                return null;
            }
            String name = input.trim();
            if (name.isEmpty()) {
                Messages.showErrorDialog(this, "Give the template a name.", title);
                continue;
            }
            if (PomTemplateService.getInstance().exists(name)) {
                Messages.showErrorDialog(this,
                        "A template named \"" + name + "\" already exists.",
                        title);
                continue;
            }
            return name;
        }
    }

    /** Runs "mvn clean install" in the chosen directory (wrapper preferred, like all builds). */
    private void runCleanInstall() {
        if (!ProjectTrust.isTrusted(project)) {
            Notifier.warn(project, "Maven was not run because this project is open in safe mode. "
                    + "Trust the project first if you want to execute its build.");
            return;
        }
        String text = directoryField.getText().trim();
        File dir = text.isEmpty() ? null : new File(text);
        if (dir == null || !dir.isDirectory()) {
            Notifier.warn(project, "Choose a valid project directory first.");
            return;
        }
        if (!new File(dir, "pom.xml").isFile()) {
            Notifier.warn(project, "No pom.xml in " + dir.getAbsolutePath() + ". There is nothing to build.");
            return;
        }
        if (com.zukotuutori.mavenassist.maven.MavenBuildRunner.findMavenExecutable(dir) == null) {
            offerMavenInstall(dir);
            return;
        }
        // With the "before clean install" trigger on, bump the flagged dependencies first and
        // build afterwards, so the install picks up the new versions. Otherwise this is a no-op
        // that just calls the build straight through.
        AutoUpdateRunner.runIfEnabled(project, AutoUpdateSettings.Trigger.BEFORE_CLEAN_INSTALL,
                new File(dir, "pom.xml"), this::refresh,
                () -> com.zukotuutori.mavenassist.maven.MavenBuildRunner.run(project, dir,
                        new String[]{"clean", "install"}, null));
    }

    /** Maven is missing: ask where to install it (project vs. whole PC), install, then build. */
    private void offerMavenInstall(@NotNull File dir) {
        int answer = com.intellij.openapi.ui.Messages.showYesNoCancelDialog(project,
                "Maven (mvn) was not found on this system. Where should it be installed?\n\n"
                        + "• In this project: downloaded from apache.org into "
                        + new File(dir, ".mavenassist").getAbsolutePath() + ". It is used for this project only.\n"
                        + "• On this PC: via Homebrew/winget if available, otherwise downloaded "
                        + "to ~/.mavenassist. It is used for all projects.\n\n"
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

    /** Rebuilds the table's columns after the visible fields were changed. */
    private void onCustomizationChanged() {
        tablePanel.applyCustomization();
    }

    private @NotNull JPanel buildEmptyCard() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
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
        // Remember it: the project-open trigger runs without this panel and reads it from there.
        MavenAssistProjectSettings.getInstance(project).setDirectory(dir);
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

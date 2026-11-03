package com.zukotuutori.mavenassist.ui;

import com.zukotuutori.mavenassist.maven.MavenCentralClient;
import com.zukotuutori.mavenassist.model.Dependency;
import com.zukotuutori.mavenassist.services.DependencyManagerService;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.IdeBorderFactory;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Two-step dialog for adding dependencies from the manager.
 * Step 1: search bar + table showing only a checkbox, the dependency's <b>name</b> and a
 * <b>Show Details</b> button per row. Step 2 (after <i>Next</i>): one block per selected
 * dependency with its own "use newest version" / specific version / auto-update choices.
 * only <i>Add</i> on that step actually adds them.
 */
public class AddDependenciesDialog extends DialogWrapper {

    private static final String STEP_PICK = "pick";
    private static final String STEP_CONFIGURE = "configure";

    private final SearchTextField searchField = new SearchTextField();
    private final DependencyPickerTable picker = new DependencyPickerTable(this::showDetails);

    private final CardLayout cardLayout = new CardLayout();
    private final JPanel cards = new JPanel(cardLayout);
    private final JPanel configureListPanel = new JPanel();

    /** groupId:artifactId -> version pinned via the details dialog (prefills step 2). */
    private final Map<String, String> versionOverrides = new HashMap<>();
    /** Step-2 widgets per selected dependency, in selection order. */
    private final Map<String, RowConfig> rowConfigs = new LinkedHashMap<>();

    private final Action backAction = new AbstractAction("< Back") {
        @Override
        public void actionPerformed(ActionEvent e) {
            showPickStep();
        }
    };

    private final Action addToManagerAction = new AbstractAction("Add Own to Manager...") {
        @Override
        public void actionPerformed(ActionEvent e) {
            // Reload regardless of OK/Cancel: the dialog's restore button changes the catalog too.
            new AddToManagerDialog(project).showAndGet();
            reloadPicker();
        }
    };

    private final @Nullable Project project;
    private final Set<String> alreadyInPomKeys;

    private boolean onConfigureStep = false;

    public AddDependenciesDialog(@Nullable Project project, @NotNull Set<String> alreadyInPomKeys) {
        super(project, true);
        this.project = project;
        this.alreadyInPomKeys = alreadyInPomKeys;
        setOKButtonText("Next");

        reloadPicker();
        picker.bindSearch(searchField);

        backAction.setEnabled(false);
        init();
        showPickStep();
    }

    /** (Re-)fills the picker from the manager; checked rows stay checked (matched by key). */
    private void reloadPicker() {
        picker.setDependencies(DependencyManagerService.getInstance().getAllDependencies().stream()
                .filter(d -> !alreadyInPomKeys.contains(d.getKey()))
                .toList());
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        cards.setPreferredSize(new Dimension(JBUI.scale(540), JBUI.scale(440)));
        cards.add(buildPickStep(), STEP_PICK);
        cards.add(buildConfigureStep(), STEP_CONFIGURE);
        return cards;
    }

    private @NotNull JPanel buildPickStep() {
        JPanel north = new JPanel(new BorderLayout(0, JBUI.scale(2)));
        north.add(searchField, BorderLayout.NORTH);
        JPanel selectRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, JBUI.scale(4), 0));
        JButton selectAllButton = new JButton("Select All");
        selectAllButton.setToolTipText("Tick every dependency currently shown (search applied)");
        selectAllButton.addActionListener(e -> picker.checkAllVisible());
        selectRow.add(selectAllButton);
        JButton selectNoneButton = new JButton("Select None");
        selectNoneButton.addActionListener(e -> picker.checkNone());
        selectRow.add(selectNoneButton);
        north.add(selectRow, BorderLayout.CENTER);

        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        panel.add(north, BorderLayout.NORTH);
        panel.add(new JBScrollPane(picker), BorderLayout.CENTER);
        panel.add(new JBLabel("Tick the dependencies you want, then press Next to choose versions."),
                BorderLayout.SOUTH);
        return panel;
    }

    private @NotNull JPanel buildConfigureStep() {
        configureListPanel.setLayout(new BoxLayout(configureListPanel, BoxLayout.Y_AXIS));

        JPanel north = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        north.add(new JBLabel("Choose the version and auto-update behavior for each dependency:"),
                BorderLayout.NORTH);
        JPanel bulkRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, JBUI.scale(4), 0));
        JButton autoUpdateAllButton = new JButton("Enable Auto Update for All");
        autoUpdateAllButton.setToolTipText(
                "Enable auto update for every dependency in this list");
        autoUpdateAllButton.addActionListener(e -> {
            for (RowConfig config : rowConfigs.values()) {
                config.autoUpdateCheck.setSelected(true);
            }
        });
        bulkRow.add(autoUpdateAllButton);
        north.add(bulkRow, BorderLayout.CENTER);

        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        panel.add(north, BorderLayout.NORTH);
        JPanel scrollAnchor = new JPanel(new BorderLayout());
        scrollAnchor.add(configureListPanel, BorderLayout.NORTH);
        panel.add(new JBScrollPane(scrollAnchor), BorderLayout.CENTER);
        return panel;
    }

    @Override
    protected Action @NotNull [] createLeftSideActions() {
        return new Action[]{backAction, addToManagerAction};
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return searchField;
    }

    // ---------------------------------------------------------------- wizard flow

    @Override
    protected void doOKAction() {
        if (!onConfigureStep) {
            if (picker.getCheckedDependencies().isEmpty()) {
                Messages.showInfoMessage(picker, "Tick at least one dependency first.", "Add Dependencies");
                return;
            }
            showConfigureStep();
            return;
        }
        setErrorText(null);
        super.doOKAction();
    }

    private void showPickStep() {
        setErrorText(null);
        onConfigureStep = false;
        setTitle("Add Dependencies (1/2): Pick");
        setOKButtonText("Next");
        backAction.setEnabled(false);
        cardLayout.show(cards, STEP_PICK);
        searchField.requestFocusInWindow();
    }

    private void showConfigureStep() {
        rebuildRowConfigs();
        onConfigureStep = true;
        setTitle("Add Dependencies (2/2): Versions & Auto Update");
        setOKButtonText("Add");
        backAction.setEnabled(true);
        cardLayout.show(cards, STEP_CONFIGURE);
    }

    /** One config block per checked dependency; earlier choices survive Back/Next round trips. */
    private void rebuildRowConfigs() {
        Map<String, RowConfig> previous = new LinkedHashMap<>(rowConfigs);
        rowConfigs.clear();
        configureListPanel.removeAll();
        for (Dependency d : picker.getCheckedDependencies()) {
            RowConfig config = previous.get(d.getKey());
            if (config == null) {
                config = new RowConfig(d);
            }
            rowConfigs.put(d.getKey(), config);
            configureListPanel.add(config.panel);
        }
        configureListPanel.revalidate();
        configureListPanel.repaint();
    }

    // ---------------------------------------------------------------- step-2 rows

    /** Version + auto-update choices for one dependency. */
    private class RowConfig {
        final Dependency dependency;
        final JBCheckBox newestCheck =
                new JBCheckBox("Use newest version (from Maven Central)", true);
        final JBTextField versionField = new JBTextField();
        final JBCheckBox autoUpdateCheck =
                new JBCheckBox("Auto update at the times selected under Customize", false);
        final JPanel panel;

        RowConfig(@NotNull Dependency dependency) {
            this.dependency = dependency;

            String pinned = versionOverrides.get(dependency.getKey());
            if (pinned != null) {
                newestCheck.setSelected(false);
                versionField.setText(pinned);
            } else if (!dependency.getVersion().isEmpty()) {
                versionField.setText(dependency.getVersion());
            }
            versionField.setEnabled(!newestCheck.isSelected());
            newestCheck.addActionListener(e -> versionField.setEnabled(!newestCheck.isSelected()));

            JPanel versionRow = new JPanel(new BorderLayout(JBUI.scale(6), 0));
            versionRow.add(new JBLabel("Specific version:"), BorderLayout.WEST);
            versionRow.add(versionField, BorderLayout.CENTER);
            versionRow.setBorder(JBUI.Borders.emptyLeft(22));

            panel = new JPanel(new java.awt.GridLayout(0, 1, 0, JBUI.scale(2)));
            panel.setBorder(IdeBorderFactory.createTitledBorder(
                    DependencyPickerTable.displayName(dependency), false));
            panel.add(newestCheck);
            panel.add(versionRow);
            panel.add(autoUpdateCheck);
        }

        boolean hasFixedVersion() {
            return !newestCheck.isSelected() && !versionField.getText().trim().isEmpty();
        }

        @NotNull String fixedVersion() {
            return versionField.getText().trim();
        }
    }

    // ---------------------------------------------------------------- details

    private void showDetails(@NotNull Dependency dependency) {
        new AddableDetailsDialog(dependency).show();
        picker.refresh();
    }

    private void changeVersion(@NotNull Dependency dependency) {
        String current = versionOverrides.getOrDefault(dependency.getKey(), dependency.getVersion());
        String input = VersionInput.ask(project, picker,
                dependency.getGroupId(), dependency.getArtifactId(),
                "Custom version for " + dependency.getKey() + "\n(leave empty to use the newest version again):",
                current);
        if (input == null) {
            return; // cancelled
        }
        if (input.isEmpty()) {
            versionOverrides.remove(dependency.getKey());
            rowConfigs.remove(dependency.getKey()); // rebuilt from the new pin on the next step change
            picker.refresh();
        } else {
            pinVersion(dependency, input);
        }
    }

    /** Pins a version chosen from the full Maven Central list. */
    private void chooseVersionFromList(@NotNull Dependency dependency) {
        String current = versionOverrides.getOrDefault(dependency.getKey(), dependency.getVersion());
        String chosen = VersionListDialog.choose(project, picker,
                dependency.getGroupId(), dependency.getArtifactId(), current);
        if (chosen != null) {
            pinVersion(dependency, chosen);
        }
    }

    private void pinVersion(@NotNull Dependency dependency, @NotNull String version) {
        versionOverrides.put(dependency.getKey(), version);
        picker.setChecked(dependency.getKey(), true); // picking a version implies you want the dependency
        rowConfigs.remove(dependency.getKey()); // rebuilt from the new pin on the next step change
        picker.refresh();
    }

    // ---------------------------------------------------------------- results

    /** Checked dependencies; ones with a specific version chosen in step 2 already carry it. */
    public @NotNull List<Dependency> getSelectedDependencies() {
        List<Dependency> result = new ArrayList<>();
        for (RowConfig config : rowConfigs.values()) {
            result.add(config.hasFixedVersion()
                    ? config.dependency.withVersion(config.fixedVersion())
                    : config.dependency);
        }
        return result;
    }

    /** Keys with a specific version chosen in step 2; never replaced by the newest one. */
    public @NotNull Set<String> getFixedVersionKeys() {
        Set<String> result = new HashSet<>();
        for (RowConfig config : rowConfigs.values()) {
            if (config.hasFixedVersion()) {
                result.add(config.dependency.getKey());
            }
        }
        return result;
    }

    /** Keys flagged in step 2 for auto-update at the configured trigger times. */
    public @NotNull Set<String> getAutoUpdateKeys() {
        Set<String> result = new HashSet<>();
        for (RowConfig config : rowConfigs.values()) {
            if (config.autoUpdateCheck.isSelected()) {
                result.add(config.dependency.getKey());
            }
        }
        return result;
    }

    // ---------------------------------------------------------------- details dialog

    /**
     * Details of one addable dependency: coordinates, catalog/pinned version and the
     * newest version from Maven Central, plus "All Versions..." and "Change Version..." to pin one.
     */
    private class AddableDetailsDialog extends DialogWrapper {
        private final Dependency dependency;
        private final JBLabel newestLabel = new JBLabel("fetching...");
        private final JBLabel pinnedLabel = new JBLabel();

        AddableDetailsDialog(@NotNull Dependency dependency) {
            super(picker, false);
            this.dependency = dependency;
            setTitle("Dependency Details");
            setOKButtonText("Close");
            init();
            fetchNewestVersion();
        }

        @Override
        protected Action @NotNull [] createActions() {
            return new Action[]{getOKAction()};
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            JPanel panel = new JPanel(new GridBagLayout());
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = JBUI.insets(3, 4);
            gbc.anchor = GridBagConstraints.WEST;
            gbc.fill = GridBagConstraints.HORIZONTAL;

            int row = 0;
            addRow(panel, gbc, row++, "Name:", DependencyPickerTable.displayName(dependency));
            addRow(panel, gbc, row++, "Group id:", dependency.getGroupId());
            addRow(panel, gbc, row++, "Artifact id:", dependency.getArtifactId());
            addRow(panel, gbc, row++, "Catalog version:",
                    dependency.getVersion().isEmpty() ? "(newest when added)" : dependency.getVersion());

            gbc.gridy = row++;
            gbc.gridx = 0;
            gbc.weightx = 0;
            panel.add(new JBLabel("Pinned version:"), gbc);
            gbc.gridx = 1;
            gbc.weightx = 1;
            updatePinnedLabel();
            pinnedLabel.setFont(pinnedLabel.getFont().deriveFont(Font.BOLD));
            panel.add(pinnedLabel, gbc);

            gbc.gridy = row++;
            gbc.gridx = 0;
            gbc.weightx = 0;
            panel.add(new JBLabel("Newest version:"), gbc);
            gbc.gridx = 1;
            gbc.weightx = 1;
            newestLabel.setFont(newestLabel.getFont().deriveFont(Font.BOLD));
            panel.add(newestLabel, gbc);

            JPanel versionButtons = new JPanel(new java.awt.FlowLayout(
                    java.awt.FlowLayout.LEFT, JBUI.scale(4), 0));
            JButton listButton = new JButton("All Versions...");
            listButton.setToolTipText("Pick from every version Maven Central publishes");
            listButton.addActionListener(e -> {
                chooseVersionFromList(dependency);
                updatePinnedLabel();
            });
            versionButtons.add(listButton);
            JButton pinButton = new JButton("Change Version...");
            pinButton.addActionListener(e -> {
                changeVersion(dependency);
                updatePinnedLabel();
            });
            versionButtons.add(pinButton);
            gbc.gridy = row;
            gbc.gridx = 0;
            gbc.gridwidth = 2;
            panel.add(versionButtons, gbc);
            return panel;
        }

        private void updatePinnedLabel() {
            String pinned = versionOverrides.get(dependency.getKey());
            pinnedLabel.setText(UiText.html(
                    pinned == null ? "(none; newest version is used)" : pinned));
        }

        private void addRow(@NotNull JPanel panel, @NotNull GridBagConstraints gbc, int row,
                            @NotNull String label, @NotNull String value) {
            gbc.gridy = row;
            gbc.gridwidth = 1;
            gbc.gridx = 0;
            gbc.weightx = 0;
            panel.add(new JBLabel(label), gbc);
            gbc.gridx = 1;
            gbc.weightx = 1;
            JBLabel valueLabel = new JBLabel(UiText.html(value));
            valueLabel.setFont(valueLabel.getFont().deriveFont(Font.BOLD));
            panel.add(valueLabel, gbc);
        }

        private void fetchNewestVersion() {
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                String latest = MavenCentralClient.getLatestVersion(
                        dependency.getGroupId(), dependency.getArtifactId());
                ApplicationManager.getApplication().invokeLater(() -> newestLabel.setText(
                        latest == null ? "? (Maven Central not reachable or artifact unknown)" : latest));
            });
        }
    }
}

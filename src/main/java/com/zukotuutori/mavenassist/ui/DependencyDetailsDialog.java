package com.zukotuutori.mavenassist.ui;

import com.zukotuutori.mavenassist.maven.MavenCentralClient;
import com.zukotuutori.mavenassist.maven.VersionPolicy;
import com.zukotuutori.mavenassist.model.Dependency;
import com.zukotuutori.mavenassist.services.AutoUpdateService;
import com.zukotuutori.mavenassist.services.UiCustomizationService;
import com.zukotuutori.mavenassist.services.UiCustomizationService.Field;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.io.File;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Shows everything about one dependency: name, coordinates, current and newest
 * version, the auto-update flag, and the update actions.
 */
public class DependencyDetailsDialog extends DialogWrapper {

    private final Dependency dependency;
    private final Project project;
    private final File pom;
    private final BiConsumer<Dependency, String> versionApplier;
    private final Consumer<String> latestVersionCache;

    private final JBLabel newestLabel = new JBLabel("fetching...");
    private final JButton updateButton = new JButton("Update to Newest Version");
    private final JButton changeVersionButton = new JButton("Change Version...");
    private final JPanel buttonsStack = new JPanel();

    private @Nullable String newestVersion;

    /**
     * @param cachedLatest       already-known newest version, or null/blank/"?" to fetch it now
     * @param versionApplier     writes the given version into the pom and builds
     * @param latestVersionCache receives the newest version once fetched (for table caching)
     */
    public DependencyDetailsDialog(@NotNull Project project,
                                   @NotNull Dependency dependency,
                                   @Nullable String cachedLatest,
                                   @NotNull File pom,
                                   @NotNull BiConsumer<Dependency, String> versionApplier,
                                   @NotNull Consumer<String> latestVersionCache) {
        super(project, true);
        this.project = project;
        this.dependency = dependency;
        this.pom = pom;
        this.versionApplier = versionApplier;
        this.latestVersionCache = latestVersionCache;

        setTitle("Dependency Details");
        setOKButtonText("Close");
        init();

        if (cachedLatest != null && !cachedLatest.isBlank() && !"?".equals(cachedLatest)) {
            setNewestVersion(cachedLatest);
        } else {
            updateButton.setEnabled(false);
            fetchNewestVersion();
        }
    }

    @Override
    protected Action @NotNull [] createActions() {
        return new Action[]{getOKAction()};
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        UiCustomizationService custom = UiCustomizationService.getInstance();
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = JBUI.insets(3, 4);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;
        if (custom.isShownInDetails(Field.NAME)) {
            addRow(panel, gbc, row++, "Name:",
                    dependency.getName().isEmpty() ? dependency.getArtifactId() : dependency.getName());
        }
        if (custom.isShownInDetails(Field.GROUP_ID)) {
            addRow(panel, gbc, row++, "Group id:", dependency.getGroupId());
        }
        if (custom.isShownInDetails(Field.ARTIFACT_ID)) {
            addRow(panel, gbc, row++, "Artifact id:", dependency.getArtifactId());
        }
        if (!dependency.getType().isEmpty()) {
            addRow(panel, gbc, row++, "Type:", dependency.getType());
        }
        if (!dependency.getClassifier().isEmpty()) {
            addRow(panel, gbc, row++, "Classifier:", dependency.getClassifier());
        }
        if (custom.isShownInDetails(Field.VERSION)) {
            addRow(panel, gbc, row++, "Current version:",
                    dependency.getVersion().isEmpty() ? "(managed by parent)" : dependency.getVersion());
        }

        if (custom.isShownInDetails(Field.LATEST_VERSION)) {
            gbc.gridy = row++;
            gbc.gridx = 0;
            gbc.weightx = 0;
            panel.add(new JBLabel("Newest version:"), gbc);
            gbc.gridx = 1;
            gbc.weightx = 1;
            panel.add(newestLabel, gbc);
        }

        if (custom.isShownInDetails(Field.AUTO_UPDATE)) {
            JBCheckBox autoUpdateCheck = new JBCheckBox("Auto update at the times selected under Customize",
                    AutoUpdateService.getInstance(project).isAutoUpdate(pom, dependency.getKey()));
            autoUpdateCheck.addActionListener(e -> {
                boolean enable = autoUpdateCheck.isSelected();
                if (!AutoUpdateConfirmation.confirm(project, dependency, enable)) {
                    autoUpdateCheck.setSelected(!enable);
                    return;
                }
                AutoUpdateService.getInstance(project).setAutoUpdate(
                        pom, dependency.getKey(), enable);
            });
            gbc.gridy = row++;
            gbc.gridx = 0;
            gbc.gridwidth = 2;
            panel.add(autoUpdateCheck, gbc);
        }

        updateButton.addActionListener(e -> {
            if (newestVersion != null) {
                versionApplier.accept(dependency, newestVersion);
                close(OK_EXIT_CODE);
            }
        });
        changeVersionButton.addActionListener(e -> changeVersion());

        buttonsStack.setLayout(new javax.swing.BoxLayout(buttonsStack, javax.swing.BoxLayout.Y_AXIS));
        buttonsStack.add(updateButton);
        buttonsStack.add(changeVersionButton);
        gbc.gridy = row;
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        panel.add(buttonsStack, gbc);

        return panel;
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
            String latest = MavenCentralClient.getLatestVersion(dependency.getGroupId(), dependency.getArtifactId());
            ApplicationManager.getApplication().invokeLater(() -> {
                if (latest == null) {
                    newestLabel.setText("? (Maven Central not reachable or artifact unknown)");
                } else {
                    setNewestVersion(latest);
                    latestVersionCache.accept(latest);
                }
            });
        });
    }

    private void setNewestVersion(@NotNull String version) {
        newestVersion = version;
        boolean managed = dependency.getVersion().isBlank();
        boolean updateAvailable = VersionPolicy.isUpgrade(dependency.getVersion(), version);
        newestLabel.setText(version + (managed ? "  (current version is managed)"
                : updateAvailable ? "" : "  (no newer stable release)"));
        newestLabel.setFont(newestLabel.getFont().deriveFont(Font.BOLD));
        updateButton.setEnabled(updateAvailable);
    }

    private void changeVersion() {
        String input = VersionInput.ask(null, getContentPanel(),
                dependency.getGroupId(), dependency.getArtifactId(),
                "Custom version for " + dependency.getKey() + ":", dependency.getVersion());
        if (input == null || input.isEmpty()) {
            return;
        }
        versionApplier.accept(dependency, input);
        close(OK_EXIT_CODE);
    }
}

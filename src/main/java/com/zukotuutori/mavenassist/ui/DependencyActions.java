package com.zukotuutori.mavenassist.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.zukotuutori.mavenassist.maven.MavenBuildRunner;
import com.zukotuutori.mavenassist.maven.MavenCentralClient;
import com.zukotuutori.mavenassist.maven.PomUtils;
import com.zukotuutori.mavenassist.maven.PomWriteAction;
import com.zukotuutori.mavenassist.maven.VersionPolicy;
import com.zukotuutori.mavenassist.model.Dependency;
import com.zukotuutori.mavenassist.services.AutoUpdateService;
import com.zukotuutori.mavenassist.util.Notifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Component;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Everything that can be done to a dependency of one pom: update it, pin a version, delete it,
 * flag it for auto update, and the same in bulk. Kept apart from any one view so the dependency
 * table and the pom.xml editor offer the same actions with the same questions and messages.
 *
 * <p>Each action writes the pom, refreshes the file in the IDE and starts a build itself; views
 * only have to say where dialogs should be anchored.</p>
 */
public final class DependencyActions {

    /** Menu labels, shared so the table and the editor never drift apart. */
    public static final String DETAILS = "Details...";
    public static final String UPDATE_NEWEST = "Update to newest version";
    public static final String ALL_VERSIONS = "Show all available versions...";
    public static final String CUSTOM_VERSION = "Custom version...";
    public static final String AUTO_UPDATE = "Auto update";
    public static final String DELETE = "Delete from pom...";

    private final Project project;
    private final File pom;
    /** Dialog owner; null anchors dialogs on the project window. */
    private final @Nullable Component parent;
    /** Run on the EDT after the pom changed, for views that do not watch the file themselves. */
    private final Runnable afterChange;

    public DependencyActions(@NotNull Project project, @NotNull File pom,
                             @Nullable Component parent, @NotNull Runnable afterChange) {
        this.project = project;
        this.pom = pom;
        this.parent = parent;
        this.afterChange = afterChange;
    }

    public DependencyActions(@NotNull Project project, @NotNull File pom) {
        this(project, pom, null, () -> {
        });
    }

    public boolean isAutoUpdate(@NotNull Dependency dep) {
        return AutoUpdateService.getInstance(project).isAutoUpdate(pom, dep.getKey());
    }

    // ---------------------------------------------------------------- single dependency

    /**
     * Moves one dependency to the newest stable release.
     *
     * @param cachedLatest a version already known from the table, or null to look it up now
     */
    public void updateToNewest(@NotNull Dependency dep, @Nullable String cachedLatest) {
        new Task.Backgroundable(project, "Maven Assist: Fetching newest version of " + dep.getKey(), true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                String latest = cachedLatest == null || cachedLatest.isEmpty() || "?".equals(cachedLatest)
                        ? MavenCentralClient.getLatestVersion(dep.getGroupId(), dep.getArtifactId())
                        : cachedLatest;
                if (latest == null) {
                    Notifier.warn(project, "Could not determine the newest version of " + dep.getKey() + ".");
                    return;
                }
                if (dep.getVersion().isBlank()) {
                    Notifier.warn(project, dep.getKey() + " is managed by a parent/BOM. "
                            + "Update that version source instead of adding an overriding <version>.");
                    return;
                }
                if (!VersionPolicy.isUpgrade(dep.getVersion(), latest)) {
                    Notifier.info(project, dep.getKey() + " is already at the newest stable version"
                            + versionSource(pom, dep) + ".");
                    return;
                }
                applyVersion(dep, latest);
            }
        }.queue();
    }

    /** Asks for a version by hand and writes it. */
    public void askCustomVersion(@NotNull Dependency dep) {
        String input = VersionInput.ask(project, parent, dep.getGroupId(), dep.getArtifactId(),
                "Custom version for " + dep.getKey() + ":", dep.getVersion());
        if (input != null && !input.isEmpty()) {
            applyVersion(dep, input);
        }
    }

    /** Lets the user pick from every version Maven Central publishes for the artifact. */
    public void chooseFromAllVersions(@NotNull Dependency dep) {
        String chosen = VersionListDialog.choose(project, parent,
                dep.getGroupId(), dep.getArtifactId(), dep.getVersion());
        if (chosen != null) {
            applyVersion(dep, chosen);
        }
    }

    /** Writes a version into the pom and rebuilds; no confirmation, the caller chose it. */
    public void applyVersion(@NotNull Dependency dep, @NotNull String newVersion) {
        new Task.Backgroundable(project, "Maven Assist: Updating " + dep.getKey(), true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                if (newVersion.equals(dep.getVersion())) {
                    Notifier.info(project, dep.getKey() + " is already at version " + newVersion + ".");
                    return;
                }
                try {
                    if (!writeVersion(dep, newVersion, null)) {
                        return;
                    }
                } catch (IOException e) {
                    Notifier.error(project, "Failed to update " + dep.getKey() + ": " + e.getMessage());
                    return;
                }
                Notifier.info(project, "Updated " + dep.getKey() + " from "
                        + (dep.getVersion().isEmpty() ? "(managed)" : dep.getVersion())
                        + " to " + newVersion + ". Building...");
                refreshAndBuild();
            }
        }.queue();
    }

    /** Asks first, then removes the whole {@code <dependency>} entry. */
    public void confirmAndDelete(@NotNull Dependency dep) {
        int answer = Messages.showYesNoDialog(project,
                "Delete " + dep.getKey() + " from pom.xml?\nThe whole <dependency> entry will be removed.",
                "Delete Dependency", "Delete", "Cancel", Messages.getWarningIcon());
        if (answer != Messages.YES) {
            return;
        }
        new Task.Backgroundable(project, "Maven Assist: Deleting " + dep.getKey(), true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    PomWriteAction.run(project, pom, "Delete Maven dependency",
                            () -> PomUtils.removeDependency(pom, dep));
                } catch (IOException e) {
                    Notifier.error(project, "Failed to delete " + dep.getKey() + ": " + e.getMessage());
                    return;
                }
                AutoUpdateService.getInstance(project).setAutoUpdate(pom, dep.getKey(), false);
                Notifier.info(project, "Deleted " + dep.getKey() + " from pom.xml. Building...");
                refreshAndBuild();
            }
        }.queue();
    }

    /**
     * Flips the auto-update flag of one dependency after the shared confirmation.
     *
     * @return true when the flag changed, so a view can re-filter its rows
     */
    public boolean toggleAutoUpdate(@NotNull Dependency dep) {
        AutoUpdateService service = AutoUpdateService.getInstance(project);
        boolean enable = !service.isAutoUpdate(pom, dep.getKey());
        if (!AutoUpdateConfirmation.confirm(project, dep, enable)) {
            return false;
        }
        service.setAutoUpdate(pom, dep.getKey(), enable);
        return true;
    }

    // ---------------------------------------------------------------- several at once

    /**
     * Moves several dependencies to their newest stable release in one pass: one progress bar,
     * one answer to the parent-property question, one summary and one build.
     *
     * @param cachedLatest versions already known per {@code groupId:artifactId}, may be empty
     * @param scope        how the set is named in the progress bar and the summary
     */
    public void updateAllToNewest(@NotNull List<Dependency> dependencies,
                                  @NotNull Map<String, String> cachedLatest,
                                  @NotNull String scope) {
        new Task.Backgroundable(project, "Maven Assist: Updating " + scope, true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                int updated = 0;
                List<String> failures = new ArrayList<>();
                List<String> unavailable = new ArrayList<>();
                AtomicReference<ParentPropertyChoice.Choice> runChoice = new AtomicReference<>();
                for (Dependency dep : dependencies) {
                    indicator.checkCanceled();
                    indicator.setText("Checking " + dep.getKey() + "...");
                    String known = cachedLatest.get(dep.getKey());
                    String latest = known == null || known.isEmpty() || "?".equals(known)
                            ? MavenCentralClient.getLatestVersion(dep.getGroupId(), dep.getArtifactId())
                            : known;
                    if (latest == null) {
                        unavailable.add(dep.getKey());
                        continue;
                    }
                    if (!VersionPolicy.isUpgrade(dep.getVersion(), latest)) {
                        continue;
                    }
                    try {
                        indicator.setText("Updating " + dep.getKey() + " to " + latest + "...");
                        if (writeVersion(dep, latest, runChoice)) {
                            updated++;
                        } else {
                            break; // cancelled: the answer holds for the rest of this update
                        }
                    } catch (IOException e) {
                        failures.add(dep.getKey() + ": " + e.getMessage());
                    }
                }
                if (!failures.isEmpty()) {
                    Notifier.error(project, "Could not update:\n" + String.join("\n", failures));
                }
                if (!unavailable.isEmpty()) {
                    Notifier.warn(project, "Could not determine the newest version for:\n"
                            + String.join("\n", unavailable)
                            + "\nThese dependencies were not changed.");
                }
                if (updated == 0) {
                    if (failures.isEmpty() && unavailable.isEmpty()) {
                        Notifier.info(project, "All dependencies are already up to date. ✔");
                    }
                    return;
                }
                Notifier.info(project, "Updated " + updated + " " + scope
                        + " to the newest version. Building...");
                refreshAndBuild();
            }
        }.queue();
    }

    /** Lists what is about to go and asks once; true when the user confirmed. */
    public boolean confirmDelete(@NotNull List<Dependency> toDelete) {
        StringBuilder names = new StringBuilder();
        for (Dependency dep : toDelete) {
            names.append('\n').append(dep.getKey());
        }
        return Messages.showYesNoDialog(project,
                "Delete " + toDelete.size() + " dependencies from pom.xml?" + names
                        + "\n\nThe whole <dependency> entries will be removed.",
                "Delete Dependencies", "Delete", "Cancel",
                Messages.getWarningIcon()) == Messages.YES;
    }

    /** Removes the given dependencies and rebuilds; confirmation happened before. */
    public void runDelete(@NotNull List<Dependency> toDelete) {
        new Task.Backgroundable(project, "Maven Assist: Deleting dependencies", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                int deleted = 0;
                List<String> failures = new ArrayList<>();
                AutoUpdateService autoService = AutoUpdateService.getInstance(project);
                for (Dependency dep : toDelete) {
                    indicator.checkCanceled();
                    indicator.setText("Deleting " + dep.getKey() + "...");
                    try {
                        PomWriteAction.run(project, pom, "Delete Maven dependency",
                                () -> PomUtils.removeDependency(pom, dep));
                        autoService.setAutoUpdate(pom, dep.getKey(), false);
                        deleted++;
                    } catch (IOException e) {
                        failures.add(dep.getKey() + ": " + e.getMessage());
                    }
                }
                if (!failures.isEmpty()) {
                    Notifier.error(project, "Could not delete:\n" + String.join("\n", failures));
                }
                if (deleted == 0) {
                    return;
                }
                Notifier.info(project, "Deleted " + deleted + " dependencies from pom.xml. Building...");
                refreshAndBuild();
            }
        }.queue();
    }

    /**
     * Flips auto update for a whole set, asking once instead of per dependency.
     *
     * @return true when the flags changed, so a view can re-filter its rows
     */
    public boolean setAutoUpdateForAll(@NotNull List<Dependency> dependencies, boolean enable) {
        int answer = Messages.showYesNoDialog(project,
                "Are you sure you want to " + (enable ? "enable" : "disable")
                        + " auto update for " + dependencies.size() + " dependencies?",
                enable ? "Enable Auto Update" : "Disable Auto Update",
                "Yes", "No", Messages.getQuestionIcon());
        if (answer != Messages.YES) {
            return false;
        }
        AutoUpdateService service = AutoUpdateService.getInstance(project);
        for (Dependency dep : dependencies) {
            service.setAutoUpdate(pom, dep.getKey(), enable);
        }
        return true;
    }

    // ---------------------------------------------------------------- internals

    /**
     * Writes the new version of one dependency. When the version comes from a property that a
     * parent pom declares, the user decides first whether to bump it there, which moves every
     * module inheriting it, or to write the version into this pom only. Returns false when they
     * cancelled. {@code runChoice} carries that answer across a bulk update so it is asked once.
     */
    private boolean writeVersion(@NotNull Dependency dep,
                                 @NotNull String newVersion,
                                 @Nullable AtomicReference<ParentPropertyChoice.Choice> runChoice)
            throws IOException {
        PomUtils.VersionProperty property = PomUtils.findVersionProperty(pom, dep);
        if (property != null && !property.declaringPom().equals(pom)) {
            ParentPropertyChoice.Choice choice = runChoice == null ? null : runChoice.get();
            if (choice == null) {
                choice = ParentPropertyChoice.ask(project, parentPropertyQuestion(
                        dep, property, newVersion, runChoice != null));
                if (runChoice != null) {
                    runChoice.set(choice);
                }
            }
            if (choice == ParentPropertyChoice.Choice.CANCEL) {
                return false;
            }
            if (choice == ParentPropertyChoice.Choice.PARENT) {
                File declaring = property.declaringPom();
                PomWriteAction.run(project, declaring, "Update Maven version property",
                        () -> PomUtils.setProperty(declaring, property.name(), newVersion));
                return true;
            }
        }
        PomWriteAction.run(project, pom, "Update Maven dependency",
                () -> PomUtils.setDependencyVersion(pom, dep, newVersion));
        return true;
    }

    private static @NotNull String parentPropertyQuestion(@NotNull Dependency dep,
                                                          @NotNull PomUtils.VersionProperty property,
                                                          @NotNull String newVersion,
                                                          boolean appliesToWholeRun) {
        return dep.getKey() + " takes its version from the property " + property.name()
                + ", which is declared in\n" + property.declaringPom().getAbsolutePath()
                + "\n\nUpdate the property there, moving every module that inherits it to "
                + newVersion + ", or write " + newVersion + " into this pom.xml only?"
                + (appliesToWholeRun
                   ? "\n\nThis answer is used for the rest of the dependencies in this update."
                   : "");
    }

    /**
     * Names the property behind a version, so "already at the newest version" does not look like
     * the plugin failing to update a ${property} it simply had no newer version for.
     */
    static @NotNull String versionSource(@Nullable File pom, @NotNull Dependency dep) {
        if (pom == null) {
            return "";
        }
        try {
            String raw = PomUtils.readRawVersion(pom, dep);
            if (raw == null || !raw.startsWith("${")) {
                return "";
            }
            PomUtils.VersionProperty property = PomUtils.findVersionProperty(pom, dep);
            String where = property == null || property.declaringPom().equals(pom)
                    ? "" : " in " + property.declaringPom().getAbsolutePath();
            return " (" + dep.getVersion() + ", from " + raw + where + ")";
        } catch (IOException e) {
            return "";
        }
    }

    private void refreshAndBuild() {
        ApplicationManager.getApplication().invokeLater(() -> {
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(pom);
            afterChange.run();
        });
        MavenBuildRunner.build(project, pom.getParentFile(), null);
    }
}

package com.zukotuutori.mavenassist.services;

import com.zukotuutori.mavenassist.maven.MavenBuildRunner;
import com.zukotuutori.mavenassist.maven.MavenCentralClient;
import com.zukotuutori.mavenassist.maven.PomUtils;
import com.zukotuutori.mavenassist.maven.PomWriteAction;
import com.zukotuutori.mavenassist.maven.VersionPolicy;
import com.zukotuutori.mavenassist.model.Dependency;
import com.zukotuutori.mavenassist.services.AutoUpdateSettings.Trigger;
import com.zukotuutori.mavenassist.ui.AutoUpdateApprovalDialog;
import com.zukotuutori.mavenassist.util.Notifier;
import com.zukotuutori.mavenassist.util.ProjectTrust;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs one "bump every dependency flagged auto update to its newest version" pass.
 *
 * <p>Deliberately headless: it reads the dependencies straight from the pom instead of
 * from the table, so the project-open trigger behaves exactly like the triggers that
 * fire while the tool window is open.</p>
 */
public final class AutoUpdateRunner {

    private static final Object IN_FLIGHT_LOCK = new Object();
    /** Poms with a pass in flight, so overlapping triggers cannot rewrite the same file at once. */
    private static final Set<String> IN_FLIGHT = new HashSet<>();
    /**
     * User-requested builds that arrived during an existing pass. They must wait for that
     * pass; starting them immediately can compile a stale or half-rewritten pom.
     */
    private static final Map<String, List<Runnable>> WAITING_AFTERWARDS = new HashMap<>();

    private AutoUpdateRunner() {
    }

    /** Runs a pass in the background if {@code trigger} is enabled, and builds when anything changed. */
    public static void runIfEnabled(@NotNull Project project,
                                    @NotNull Trigger trigger,
                                    @Nullable File pom,
                                    @Nullable Runnable onPomChanged) {
        runIfEnabled(project, trigger, pom, onPomChanged, null);
    }

    /**
     * Runs a pass in the background if {@code trigger} is enabled.
     *
     * @param onPomChanged run on the EDT after the pom was rewritten, to refresh open UI
     * @param afterwards   run on the EDT once the pass is over, whether or not anything
     *                     changed; used to chain the build the user actually asked for.
     *                     Passing one also suppresses this pass's own "mvn compile": the
     *                     caller's build is about to cover it.
     */
    public static void runIfEnabled(@NotNull Project project,
                                    @NotNull Trigger trigger,
                                    @Nullable File pom,
                                    @Nullable Runnable onPomChanged,
                                    @Nullable Runnable afterwards) {
        if (!AutoUpdateSettings.getInstance().isTriggerEnabled(trigger)) {
            runOnEdt(afterwards);
            return;
        }
        start(project, pom, onPomChanged, afterwards);
    }

    /**
     * Runs a pass right now, without asking whether any trigger is enabled. This is for the
     * {@code MavenAssist.RunAutoUpdate} action, where invoking it is already the request.
     * Project trust is still honoured; that one is not the user's to waive here.
     */
    public static void runNow(@NotNull Project project,
                              @Nullable File pom,
                              @Nullable Runnable onPomChanged) {
        start(project, pom, onPomChanged, null);
    }

    private static void start(@NotNull Project project,
                              @Nullable File pom,
                              @Nullable Runnable onPomChanged,
                              @Nullable Runnable afterwards) {
        if (!ProjectTrust.isTrusted(project)) {
            runOnEdt(afterwards);
            return;
        }
        File target = pom != null ? pom : MavenAssistProjectSettings.watchedPom(project);
        if (target == null || !target.isFile()) {
            runOnEdt(afterwards);
            return;
        }
        String targetKey = pomKey(target);
        if (!beginPass(targetKey, afterwards)) {
            return;
        }
        boolean buildHere = afterwards == null;
        new Task.Backgroundable(project, "Maven Assist: Auto-updating dependencies", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                List<String> updated = updateFlagged(project, target, indicator);
                if (updated.isEmpty()) {
                    return;
                }
                Notifier.info(project, "Auto-updated " + updated.size() + " dependencies:\n"
                        + String.join("\n", updated) + (buildHere ? "\nBuilding..." : ""));
                ApplicationManager.getApplication().invokeLater(() -> {
                    LocalFileSystem.getInstance().refreshAndFindFileByIoFile(target);
                    runOnEdt(onPomChanged);
                });
                if (buildHere) {
                    MavenBuildRunner.build(project, target.getParentFile(), null);
                }
            }

            @Override
            public void onFinished() {
                finishPass(targetKey, afterwards);
            }
        }.queue();
    }

    /**
     * Bumps every flagged dependency in {@code pom} that has a newer release.
     *
     * @return one "groupId:artifactId → version" line per dependency actually rewritten
     */
    private static @NotNull List<String> updateFlagged(@NotNull Project project,
                                                       @NotNull File pom,
                                                       @Nullable ProgressIndicator indicator) {
        AutoUpdateService service = AutoUpdateService.getInstance(project);
        AutoUpdateSettings settings = AutoUpdateSettings.getInstance();

        List<Dependency> flagged = new ArrayList<>();
        try {
            for (Dependency dependency : PomUtils.readDependencies(pom)) {
                if (service.isAutoUpdate(pom, dependency.getKey())) {
                    flagged.add(dependency);
                }
            }
        } catch (IOException e) {
            Notifier.error(project, "Could not read " + pom.getName() + ": " + e.getMessage());
            return List.of();
        }

        List<String> updated = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        for (Dependency dependency : flagged) {
            if (indicator != null) {
                indicator.checkCanceled();
                indicator.setText("Checking " + dependency.getKey() + "...");
            }
            String latest = MavenCentralClient.getLatestVersion(
                    dependency.getGroupId(), dependency.getArtifactId());
            if (latest == null) {
                unavailable.add(dependency.getKey());
                continue;
            }
            if (!VersionPolicy.isUpgrade(dependency.getVersion(), latest)) {
                continue;
            }
            if (requiresConfirmation(dependency.getVersion(), latest,
                    settings.isConfirmNonMajorUpdates())) {
                AutoUpdateApprovalDialog.Choice choice =
                        AutoUpdateApprovalDialog.ask(project, dependency, latest);
                if (choice == AutoUpdateApprovalDialog.Choice.NEVER) {
                    service.setAutoUpdate(pom, dependency.getKey(), false);
                    Notifier.info(project, "Auto update disabled for " + dependency.getKey() + ".");
                    continue;
                }
                if (choice == AutoUpdateApprovalDialog.Choice.SKIP) {
                    skipped.add(dependency.getKey() + " " + dependency.getVersion()
                            + " → " + latest);
                    continue;
                }
            }
            try {
                PomWriteAction.run(project, pom, "Auto-update Maven dependency",
                        () -> PomUtils.setDependencyVersion(pom, dependency, latest));
                updated.add(dependency.getKey() + " → " + latest);
            } catch (IOException e) {
                Notifier.error(project,
                        "Auto-update of " + dependency.getKey() + " failed: " + e.getMessage());
            }
        }
        if (!skipped.isEmpty()) {
            Notifier.info(project, "Skipped automatic updates:\n"
                    + String.join("\n", skipped)
                    + "\nThey can be reviewed again during the next auto-update run.");
        }
        if (!unavailable.isEmpty()) {
            Notifier.warn(project, "Could not determine the newest version for:\n"
                    + String.join("\n", unavailable)
                    + "\nThese auto-update dependencies were not changed.");
        }
        return updated;
    }

    static boolean requiresConfirmation(@NotNull String currentVersion,
                                        @NotNull String latestVersion,
                                        boolean confirmNonMajorUpdates) {
        return confirmNonMajorUpdates
                || AutoUpdateApprovalDialog.isMajorUpgrade(currentVersion, latestVersion);
    }

    private static void runOnEdt(@Nullable Runnable runnable) {
        if (runnable != null) {
            ApplicationManager.getApplication().invokeLater(runnable);
        }
    }

    private static @NotNull String pomKey(@NotNull File pom) {
        return pom.toPath().toAbsolutePath().normalize().toString();
    }

    /**
     * Claims a pom for one pass. A build callback is queued instead of run when another
     * pass owns it; headless triggers without a callback can simply coalesce.
     */
    private static boolean beginPass(@NotNull String key, @Nullable Runnable afterwards) {
        synchronized (IN_FLIGHT_LOCK) {
            if (IN_FLIGHT.add(key)) {
                return true;
            }
            if (afterwards != null) {
                WAITING_AFTERWARDS.computeIfAbsent(key, ignored -> new ArrayList<>()).add(afterwards);
            }
            return false;
        }
    }

    /** Releases the pom and dispatches every callback that was waiting for a complete rewrite. */
    private static void finishPass(@NotNull String key, @Nullable Runnable afterwards) {
        List<Runnable> waiting;
        synchronized (IN_FLIGHT_LOCK) {
            IN_FLIGHT.remove(key);
            waiting = WAITING_AFTERWARDS.remove(key);
        }
        runOnEdt(afterwards);
        if (waiting != null) {
            waiting.forEach(AutoUpdateRunner::runOnEdt);
        }
    }
}

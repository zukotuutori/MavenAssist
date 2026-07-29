package com.zukotuutori.mavenassist.actions;

import com.zukotuutori.mavenassist.services.AutoUpdateRunner;
import com.zukotuutori.mavenassist.services.MavenAssistProjectSettings;
import com.zukotuutori.mavenassist.util.Notifier;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;

/**
 * Runs the auto-update pass on demand. This is what the "When I press a shortcut" trigger
 * binds a key to, and it is also reachable through Find Action.
 *
 * <p>Unlike the timed triggers this one does not consult the settings: invoking an action
 * is already an explicit request, and refusing it because a checkbox elsewhere is off would
 * only be baffling. The checkbox governs whether a key is bound to it, nothing more.</p>
 */
public final class RunAutoUpdateAction extends AnAction implements DumbAware {

    /** Must match the id in plugin.xml because the keymap is addressed by it. */
    public static final String ID = "MavenAssist.RunAutoUpdate";

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabled(e.getData(CommonDataKeys.PROJECT) != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getData(CommonDataKeys.PROJECT);
        if (project == null) {
            return;
        }
        File pom = MavenAssistProjectSettings.watchedPom(project);
        if (pom == null) {
            Notifier.warn(project, "No pom.xml found to update. Open the Maven Assist tool window "
                    + "and point it at a directory that has one.");
            return;
        }
        AutoUpdateRunner.runNow(project, pom, null);
    }

    /** Convenience for callers that already resolved the pom. */
    public static void run(@NotNull Project project, @Nullable File pom) {
        if (pom != null) {
            AutoUpdateRunner.runNow(project, pom, null);
        }
    }
}

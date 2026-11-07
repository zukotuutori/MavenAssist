package com.zukotuutori.mavenassist.actions;

import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.Separator;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.zukotuutori.mavenassist.model.Dependency;
import com.zukotuutori.mavenassist.ui.DependencyActions;
import com.zukotuutori.mavenassist.ui.DependencyDetailsDialog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * "Maven Assist" in the pom.xml editor's right-click menu. With the caret in one
 * {@code <dependency>} block it offers everything the dependency table offers for one row; with a
 * selection touching several blocks it offers the actions that make sense for all of them.
 * Outside the project's own dependency blocks it stays hidden.
 */
public class PomDependencyActionGroup extends ActionGroup {

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT; // reads PSI
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(!keys(e).isEmpty());
    }

    @Override
    public AnAction @NotNull [] getChildren(@Nullable AnActionEvent e) {
        if (e == null) {
            return EMPTY_ARRAY;
        }
        int count = keys(e).size();
        if (count == 0) {
            return EMPTY_ARRAY;
        }
        if (count == 1) {
            return new AnAction[]{
                new DetailsAction(),
                    Separator.getInstance(),
                    single(DependencyActions.UPDATE_NEWEST, (a, dep) -> a.updateToNewest(dep, null)),
                    single(DependencyActions.ALL_VERSIONS, DependencyActions::chooseFromAllVersions),
                    single(DependencyActions.CUSTOM_VERSION, DependencyActions::askCustomVersion),
                    Separator.getInstance(),
                    new AutoUpdateToggle(),
                    Separator.getInstance(),
                    single(DependencyActions.DELETE, DependencyActions::confirmAndDelete),
            };
        }
        return new AnAction[]{
                bulk("Update " + count + " dependencies to the newest version",
                        (a, deps) -> a.updateAllToNewest(deps, Map.of(), "selected dependencies")),
                Separator.getInstance(),
                bulk("Turn auto update on for " + count + " dependencies",
                        (a, deps) -> a.setAutoUpdateForAll(deps, true)),
                bulk("Turn auto update off for " + count + " dependencies",
                        (a, deps) -> a.setAutoUpdateForAll(deps, false)),
                Separator.getInstance(),
                bulk("Delete " + count + " dependencies from pom...", (a, deps) -> {
                    if (a.confirmDelete(deps)) {
                        a.runDelete(deps);
                    }
                }),
        };
    }

    // ---------------------------------------------------------------- targets

    private static @NotNull List<String> keys(@NotNull AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        PsiFile file = e.getData(CommonDataKeys.PSI_FILE);
        return editor == null || e.getProject() == null
                ? List.of() : PomDependencySelection.keys(editor, file);
    }

    /** What an action runs on, resolved when it runs: after saving, so the pom on disk is current. */
    private record Target(@NotNull Project project, @NotNull File pom, @NotNull List<Dependency> dependencies) {

        static @Nullable Target of(@NotNull AnActionEvent e) {
            Project project = e.getProject();
            Editor editor = e.getData(CommonDataKeys.EDITOR);
            PsiFile file = e.getData(CommonDataKeys.PSI_FILE);
            File pom = PomDependencySelection.pomFileOf(file);
            if (project == null || editor == null || pom == null) {
                return null;
            }
            List<String> keys = PomDependencySelection.keys(editor, file);
            FileDocumentManager.getInstance().saveDocument(editor.getDocument());
            List<Dependency> dependencies = PomDependencySelection.resolve(pom, keys);
            return dependencies.isEmpty() ? null : new Target(project, pom, dependencies);
        }

        @NotNull DependencyActions actions() {
            return new DependencyActions(project, pom);
        }
    }

    private static @NotNull AnAction single(@NotNull String text,
                                            @NotNull BiConsumer<DependencyActions, Dependency> run) {
        return new DumbAwareAction(text) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                Target target = Target.of(e);
                if (target != null) {
                    run.accept(target.actions(), target.dependencies().get(0));
                }
            }
        };
    }

    private static @NotNull AnAction bulk(@NotNull String text,
                                          @NotNull BiConsumer<DependencyActions, List<Dependency>> run) {
        return new DumbAwareAction(text) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                Target target = Target.of(e);
                if (target != null) {
                    run.accept(target.actions(), target.dependencies());
                }
            }
        };
    }

    /** The same details dialog the table's Details button opens. */
    private static final class DetailsAction extends DumbAwareAction {

        DetailsAction() {
            super(DependencyActions.DETAILS);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            Target target = Target.of(e);
            if (target == null) {
                return;
            }
            DependencyActions actions = target.actions();
            new DependencyDetailsDialog(target.project(), target.dependencies().get(0), null,
                    target.pom(), actions::applyVersion, latest -> {
            }).show();
        }
    }

    /** The auto-update switch, ticked when the dependency is flagged. */
    private static final class AutoUpdateToggle extends ToggleAction {

        AutoUpdateToggle() {
            super(DependencyActions.AUTO_UPDATE);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public boolean isSelected(@NotNull AnActionEvent e) {
            Project project = e.getProject();
            Editor editor = e.getData(CommonDataKeys.EDITOR);
            PsiFile file = e.getData(CommonDataKeys.PSI_FILE);
            File pom = PomDependencySelection.pomFileOf(file);
            if (project == null || editor == null || pom == null) {
                return false;
            }
            List<Dependency> dependencies =
                    PomDependencySelection.resolve(pom, PomDependencySelection.keys(editor, file));
            return !dependencies.isEmpty()
                    && new DependencyActions(project, pom).isAutoUpdate(dependencies.get(0));
        }

        @Override
        public void setSelected(@NotNull AnActionEvent e, boolean state) {
            Target target = Target.of(e);
            if (target != null) {
                target.actions().toggleAutoUpdate(target.dependencies().get(0));
            }
        }
    }
}

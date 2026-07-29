package com.zukotuutori.mavenassist.startup;

import com.zukotuutori.mavenassist.services.AutoUpdateRunner;
import com.zukotuutori.mavenassist.services.AutoUpdateSettings.Trigger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Runs the auto-update pass when a project finishes opening, if that trigger is on.
 * Returns immediately. The pass itself is queued as a background task, so startup
 * is never held up by network calls to Maven Central.
 */
public final class AutoUpdateOnOpenActivity implements ProjectActivity {

    @Override
    public @Nullable Object execute(@NotNull Project project,
                                    @NotNull Continuation<? super Unit> continuation) {
        AutoUpdateRunner.runIfEnabled(project, Trigger.ON_PROJECT_OPEN, null, null);
        return Unit.INSTANCE;
    }
}

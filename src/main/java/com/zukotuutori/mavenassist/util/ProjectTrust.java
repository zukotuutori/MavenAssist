package com.zukotuutori.mavenassist.util;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Checks the IDE's project trust state without taking a binary dependency on the
 * experimental TrustedProjects API. If JetBrains moves or changes that API, Maven
 * execution and automatic POM changes fail closed until this adapter is updated.
 *
 * <p>Failing closed is the safe direction, but it must not be a silent one: everything
 * that depends on trust would simply stop happening, with nothing to explain why. The
 * first failure therefore reaches both the log and the user, once per IDE session.</p>
 */
public final class ProjectTrust {

    private static final Logger LOG = Logger.getInstance(ProjectTrust.class);
    private static final String CLASS_NAME = "com.intellij.ide.impl.TrustedProjects";

    private static final Method IS_TRUSTED = findTrustMethod();
    /** So a broken adapter is reported once, not on every table repaint. */
    private static final AtomicBoolean REPORTED = new AtomicBoolean();

    private ProjectTrust() {
    }

    public static boolean isTrusted(@NotNull Project project) {
        if (IS_TRUSTED == null) {
            reportUnavailable(project, null);
            return false;
        }
        try {
            return Boolean.TRUE.equals(IS_TRUSTED.invoke(null, project));
        } catch (ReflectiveOperationException | RuntimeException e) {
            reportUnavailable(project, e);
            return false;
        }
    }

    /**
     * Whether the trust check itself works. Distinguishes "this project is not trusted"
     * from "we cannot tell"; only the second one is a defect.
     */
    public static boolean isCheckAvailable() {
        return IS_TRUSTED != null;
    }

    private static void reportUnavailable(@NotNull Project project, @Nullable Throwable cause) {
        if (!REPORTED.compareAndSet(false, true)) {
            return;
        }
        String message = "Maven Assist cannot read the IDE's project trust state ("
                + CLASS_NAME + " is missing or has changed). Everything that would run on its "
                + "own (including auto update and the builds that follow it) stays switched off until "
                + "the plugin is updated. The Update buttons in the tool window still work.";
        LOG.warn(message, cause);
        Notifier.warn(project, message);
    }

    private static @Nullable Method findTrustMethod() {
        try {
            return Class.forName(CLASS_NAME).getMethod("isTrusted", Project.class);
        } catch (ReflectiveOperationException | LinkageError e) {
            LOG.warn("Project trust API not found; automatic actions stay disabled", e);
            return null;
        }
    }
}

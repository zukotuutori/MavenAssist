package com.zukotuutori.mavenassist.util;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class Notifier {

    private static final String GROUP_ID = "Maven Assist";

    private Notifier() {
    }

    public static void info(@Nullable Project project, @NotNull String message) {
        notify(project, message, NotificationType.INFORMATION);
    }

    public static void warn(@Nullable Project project, @NotNull String message) {
        notify(project, message, NotificationType.WARNING);
    }

    public static void error(@Nullable Project project, @NotNull String message) {
        notify(project, message, NotificationType.ERROR);
    }

    private static void notify(@Nullable Project project, @NotNull String message, @NotNull NotificationType type) {
        NotificationGroupManager.getInstance()
                .getNotificationGroup(GROUP_ID)
                .createNotification("Maven Assist", message, type)
                .notify(project);
    }
}

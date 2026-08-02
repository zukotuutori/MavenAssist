package com.zukotuutori.mavenassist.ui;

import com.zukotuutori.mavenassist.model.Dependency;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.HyperlinkLabel;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Asks whether one detected automatic dependency update may be applied. */
public final class AutoUpdateApprovalDialog extends DialogWrapper {

    public enum Choice {
        /** Apply this one bump now. */
        UPDATE,
        /** Leave it at the current version for now; ask again next time. */
        SKIP,
        /** Leave it and turn auto update off for this dependency. */
        NEVER
    }

    /** Leading integer of a version: "33.4.8-jre" -> 33. Capped so a silly input cannot overflow. */
    private static final Pattern MAJOR = Pattern.compile("^(\\d{1,9})");

    private final Dependency dependency;
    private final String latestVersion;
    private Choice choice = Choice.SKIP;

    private AutoUpdateApprovalDialog(@NotNull Project project,
                                     @NotNull Dependency dependency,
                                     @NotNull String latestVersion) {
        super(project, true);
        this.dependency = dependency;
        this.latestVersion = latestVersion;
        setTitle("Auto Update Detected");
        init();
    }

    /**
     * The major version of {@code version}, or -1 when it has none that can be read.
     */
    private static int majorOf(@NotNull String version) {
        Matcher m = MAJOR.matcher(version.trim());
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /**
     * Whether going from {@code current} to {@code latest} crosses a major version.
     *
     * <p>An empty current version means the version is managed elsewhere, so there is
     * nothing to compare and nothing to warn about. If either side has no readable major,
     * this errs towards asking: an unexpected question is a smaller problem than an
     * unattended rewrite that breaks the build.</p>
     */
    public static boolean isMajorUpgrade(@NotNull String current, @NotNull String latest) {
        if (current.isBlank()) {
            return false;
        }
        int from = majorOf(current);
        int to = majorOf(latest);
        if (from < 0 || to < 0) {
            return true;
        }
        return to > from;
    }

    /** The artifact's page on Maven Central, which links on to the project's own site and SCM. */
    private @NotNull String releasePageUrl() {
        return "https://central.sonatype.com/artifact/"
                + pathSegment(dependency.getGroupId()) + "/" + pathSegment(dependency.getArtifactId())
                + "/" + pathSegment(latestVersion);
    }

    private static @NotNull String pathSegment(@NotNull String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * Shows the dialog on the EDT and blocks the calling background thread until it is
     * answered, which is also what makes several of these queue up one after another
     * instead of all at once.
     */
    public static @NotNull Choice ask(@NotNull Project project,
                                      @NotNull Dependency dependency,
                                      @NotNull String latestVersion) {
        AtomicReference<Choice> answer = new AtomicReference<>(Choice.SKIP);
        ApplicationManager.getApplication().invokeAndWait(() -> {
            AutoUpdateApprovalDialog dialog =
                    new AutoUpdateApprovalDialog(project, dependency, latestVersion);
            dialog.show();
            answer.set(dialog.choice);
        });
        return answer.get();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        panel.setBorder(JBUI.Borders.empty(4));

        String name = dependency.getName().isEmpty()
                ? dependency.getKey()
                : dependency.getName() + " (" + dependency.getKey() + ")";
        boolean major = isMajorUpgrade(dependency.getVersion(), latestVersion);
        String majorWarning = major
                ? "<br><br>This is a major version update and may contain breaking changes."
                : "";
        panel.add(new JBLabel("<html>Auto update detected a new version for <b>" + html(name)
                + "</b>.<br><br>Do you want to update from <b>"
                + html(dependency.getVersion()) + "</b> to <b>" + html(latestVersion) + "</b>?"
                + majorWarning + "</html>"),
                BorderLayout.NORTH);

        HyperlinkLabel link = new HyperlinkLabel("View release information and changes");
        link.setHyperlinkTarget(releasePageUrl());
        link.setToolTipText("Opens the Maven Central release page, which links to the project's "
                + "website, source code and release notes when provided.");
        panel.add(link, BorderLayout.CENTER);

        JBLabel hint = new JBLabel("\"Never\" only turns auto update off for this one dependency. "
                + "You can turn it back on in the table at any time.");
        hint.setForeground(UIUtil.getContextHelpForeground());
        panel.add(hint, BorderLayout.SOUTH);
        return panel;
    }

    private static @NotNull String html(@NotNull String value) {
        return com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(value);
    }

    @Override
    protected Action @NotNull [] createActions() {
        return new Action[]{
                action("Yes", Choice.UPDATE),
                action("No", Choice.SKIP),
                action("Never", Choice.NEVER),
        };
    }

    private @NotNull Action action(@NotNull String text, @NotNull Choice result) {
        return new DialogWrapperAction(text) {
            @Override
            protected void doAction(java.awt.event.ActionEvent e) {
                choice = result;
                close(result == Choice.UPDATE ? OK_EXIT_CODE : CANCEL_EXIT_CODE);
            }
        };
    }

    /** Closing with Esc or the window button means "leave it alone for now". */
    @Override
    public void doCancelAction() {
        choice = Choice.SKIP;
        super.doCancelAction();
    }
}

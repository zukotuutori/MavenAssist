package com.zukotuutori.mavenassist.maven;

import com.zukotuutori.mavenassist.util.Notifier;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.PathEnvironmentVariableUtil;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Runs "mvn compile" in a background task and reports the result as a notification. */
public final class MavenBuildRunner {

    private static final int TIMEOUT_MS = 10 * 60 * 1000;
    /** Version downloaded from apache.org when no package manager is available. */
    private static final String MAVEN_VERSION = "3.9.11";
    /** Official Apache SHA-512 of apache-maven-3.9.11-bin.zip; the download is rejected on mismatch. */
    private static final String MAVEN_SHA512 =
            "03e2d65d4483a3396980629f260e25cac0d8b6f7f2791e4dc20bc83f9514db8d0"
            + "f05b0479e699a5f34679250c49c8e52e961262ded468a20de0be254d8207076";

    private MavenBuildRunner() {
    }

    public static void build(@Nullable Project project, @NotNull File workingDir, @Nullable Runnable onFinishedEdt) {
        run(project, workingDir, new String[]{"compile"}, onFinishedEdt);
    }

    /** Runs "mvn &lt;goals&gt;" in a background task and reports the result as a notification. */
    public static void run(@Nullable Project project, @NotNull File workingDir,
                           @NotNull String[] goals, @Nullable Runnable onFinishedEdt) {
        String goalText = String.join(" ", goals);
        new Task.Backgroundable(project, "Maven Assist: Running mvn " + goalText, true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setText("Running mvn " + goalText + " in " + workingDir.getName() + "...");
                String maven = findMavenExecutable(workingDir);
                if (maven == null) {
                    Notifier.warn(project, "No Maven executable (mvn) was found on the PATH — mvn " + goalText + " skipped.");
                    return;
                }
                try {
                    java.util.List<String> commandLine = new java.util.ArrayList<>();
                    commandLine.add(maven);
                    commandLine.add("-B");
                    commandLine.addAll(java.util.List.of(goals));
                    GeneralCommandLine command = new GeneralCommandLine(commandLine)
                            .withWorkDirectory(workingDir);
                    if (System.getenv("JAVA_HOME") == null) {
                        // The mvn script needs a JDK; fall back to the one running the IDE.
                        command.withEnvironment("JAVA_HOME", System.getProperty("java.home"));
                    }
                    ProcessOutput output = new CapturingProcessHandler(command).runProcess(TIMEOUT_MS);
                    if (output.isTimeout()) {
                        Notifier.error(project, "Maven build timed out after 10 minutes.");
                    } else if (output.getExitCode() == 0) {
                        Notifier.info(project, "Maven build succeeded (mvn " + goalText + ").");
                    } else {
                        Notifier.error(project, "Maven build failed (exit code " + output.getExitCode() + "):\n"
                                + tail(output.getStdout() + "\n" + output.getStderr()));
                    }
                } catch (Exception e) {
                    Notifier.error(project, "Could not run Maven: " + e.getMessage());
                }
            }

            @Override
            public void onFinished() {
                if (onFinishedEdt != null) {
                    ApplicationManager.getApplication().invokeLater(onFinishedEdt);
                }
            }
        }.queue();
    }

    /** Where an on-demand Maven installation should live. */
    public enum InstallScope {
        /** Downloaded into &lt;project&gt;/.mavenassist — used for that project only. */
        PROJECT,
        /** Package manager if available, otherwise ~/.mavenassist — used everywhere. */
        MACHINE
    }

    /**
     * Installs Maven. {@link InstallScope#MACHINE} uses the platform's package manager
     * (Homebrew on macOS, winget on Windows) when one exists and otherwise downloads the
     * official binaries from apache.org into <code>~/.mavenassist</code>;
     * {@link InstallScope#PROJECT} always downloads into <code>&lt;project&gt;/.mavenassist</code>.
     * Both locations are found automatically by all builds afterwards.
     */
    public static void installMaven(@Nullable Project project, @NotNull InstallScope scope,
                                    @NotNull File projectDir, @Nullable Runnable onSuccessEdt) {
        String[] installCommand = scope == InstallScope.MACHINE ? installCommand() : null;
        new Task.Backgroundable(project, "Maven Assist: Installing Maven", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    if (installCommand != null) {
                        String commandText = String.join(" ", installCommand);
                        indicator.setText("Running " + commandText + "...");
                        GeneralCommandLine command = new GeneralCommandLine(installCommand);
                        ProcessOutput output = new CapturingProcessHandler(command).runProcess(TIMEOUT_MS);
                        if (output.isTimeout()) {
                            Notifier.error(project, "Installing Maven timed out after 10 minutes.");
                            return;
                        }
                        if (output.getExitCode() != 0) {
                            Notifier.error(project, "Installing Maven failed (exit code " + output.getExitCode()
                                    + "):\n" + tail(output.getStdout() + "\n" + output.getStderr()));
                            return;
                        }
                        Notifier.info(project, "Maven installed successfully (" + commandText + ").");
                    } else {
                        File base = scope == InstallScope.PROJECT
                                ? new File(projectDir, ".mavenassist")
                                : machineMavenBaseDir();
                        downloadAndUnpackMaven(indicator, base);
                        Notifier.info(project, "Maven " + MAVEN_VERSION
                                + " downloaded from apache.org and installed to " + base.getAbsolutePath() + ".");
                    }
                    if (onSuccessEdt != null) {
                        ApplicationManager.getApplication().invokeLater(onSuccessEdt);
                    }
                } catch (Exception e) {
                    Notifier.error(project, "Could not install Maven: " + e.getMessage()
                            + "\nYou can install it manually from https://maven.apache.org/install.html.");
                }
            }
        }.queue();
    }

    /** Downloads the official Maven binary zip and unpacks it into the given base directory. */
    private static void downloadAndUnpackMaven(@NotNull ProgressIndicator indicator,
                                               @NotNull File baseDir) throws Exception {
        String zipName = "apache-maven-" + MAVEN_VERSION + "-bin.zip";
        String[] urls = {
                "https://dlcdn.apache.org/maven/maven-3/" + MAVEN_VERSION + "/binaries/" + zipName,
                "https://archive.apache.org/dist/maven/maven-3/" + MAVEN_VERSION + "/binaries/" + zipName,
        };
        Path tempZip = Files.createTempFile("mavenassist-maven", ".zip");
        Exception lastFailure = null;
        boolean downloaded = false;
        for (String url : urls) {
            indicator.checkCanceled();
            indicator.setText("Downloading " + url + "...");
            try (InputStream in = URI.create(url).toURL().openStream()) {
                Files.copy(in, tempZip, StandardCopyOption.REPLACE_EXISTING);
                downloaded = true;
                break;
            } catch (Exception e) {
                lastFailure = e;
            }
        }
        if (!downloaded) {
            throw lastFailure != null ? lastFailure : new IOException("Download failed.");
        }

        indicator.setText("Verifying download...");
        String actual = sha512(tempZip);
        if (!MAVEN_SHA512.equalsIgnoreCase(actual)) {
            Files.deleteIfExists(tempZip);
            throw new IOException("Downloaded Maven failed its checksum check and was discarded "
                    + "(expected " + MAVEN_SHA512 + ", got " + actual + "). Nothing was installed.");
        }

        indicator.setText("Unpacking Maven...");
        Path base = baseDir.toPath();
        Files.createDirectories(base);
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(tempZip))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path target = base.resolve(entry.getName()).normalize();
                if (!target.startsWith(base)) {
                    continue; // zip-slip guard
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        Files.deleteIfExists(tempZip);

        File mvn = managedMavenExecutable(baseDir);
        if (mvn == null) {
            throw new IOException("mvn executable missing after unpacking.");
        }
        if (!SystemInfo.isWindows) {
            //noinspection ResultOfMethodCallIgnored
            mvn.setExecutable(true);
        }
    }

    /** Lowercase hex SHA-512 of the file. */
    private static @NotNull String sha512(@NotNull Path file) throws IOException {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-512");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-512 unavailable", e);
        }
    }

    private static @NotNull File machineMavenBaseDir() {
        return new File(System.getProperty("user.home"), ".mavenassist");
    }

    /** The mvn executable of a Maven previously downloaded into the given base dir, or null. */
    private static @Nullable File managedMavenExecutable(@NotNull File baseDir) {
        File[] installations = baseDir.listFiles((dir, name) -> name.startsWith("apache-maven-"));
        if (installations == null) {
            return null;
        }
        String binary = SystemInfo.isWindows ? "mvn.cmd" : "mvn";
        for (File installation : installations) {
            File mvn = new File(installation, "bin" + File.separator + binary);
            if (mvn.isFile()) {
                return mvn;
            }
        }
        return null;
    }

    /** The package-manager command that installs Maven on this platform, or null if none is available. */
    private static String @Nullable [] installCommand() {
        if (SystemInfo.isMac) {
            for (String brew : new String[]{"/opt/homebrew/bin/brew", "/usr/local/bin/brew"}) {
                if (new File(brew).isFile()) {
                    return new String[]{brew, "install", "maven"};
                }
            }
            File onPath = PathEnvironmentVariableUtil.findInPath("brew");
            return onPath == null ? null : new String[]{onPath.getAbsolutePath(), "install", "maven"};
        }
        if (SystemInfo.isWindows) {
            File winget = PathEnvironmentVariableUtil.findInPath("winget.exe");
            return winget == null ? null : new String[]{winget.getAbsolutePath(), "install", "-e",
                    "--id", "Apache.Maven", "--accept-source-agreements", "--accept-package-agreements"};
        }
        return null; // Linux package managers need sudo — no silent install possible.
    }

    public static @Nullable String findMavenExecutable(@NotNull File workingDir) {
        // Prefer the project's Maven wrapper if present.
        File wrapper = new File(workingDir, SystemInfo.isWindows ? "mvnw.cmd" : "mvnw");
        if (wrapper.isFile() && (SystemInfo.isWindows || wrapper.canExecute())) {
            return wrapper.getAbsolutePath();
        }
        String executableName = SystemInfo.isWindows ? "mvn.cmd" : "mvn";
        File onPath = PathEnvironmentVariableUtil.findInPath(executableName);
        if (onPath != null) {
            return onPath.getAbsolutePath();
        }
        for (String candidate : new String[]{"/opt/homebrew/bin/mvn", "/usr/local/bin/mvn", "/usr/bin/mvn"}) {
            File file = new File(candidate);
            if (file.isFile()) {
                return file.getAbsolutePath();
            }
        }
        // A Maven previously downloaded by this plugin (see installMaven):
        // per-project first, then the machine-wide copy.
        File projectLocal = managedMavenExecutable(new File(workingDir, ".mavenassist"));
        if (projectLocal != null) {
            return projectLocal.getAbsolutePath();
        }
        File machine = managedMavenExecutable(machineMavenBaseDir());
        if (machine != null) {
            return machine.getAbsolutePath();
        }
        // Last resort: the Maven distribution bundled with the IDE ("Bundled (Maven 3)").
        return bundledIdeMavenExecutable();
    }

    /** The mvn of the Maven distribution shipped inside the IDE, or null (not every IDE has it). */
    private static @Nullable String bundledIdeMavenExecutable() {
        File mvn = new File(com.intellij.openapi.application.PathManager.getHomePath(),
                "plugins/maven/lib/maven3/bin/" + (SystemInfo.isWindows ? "mvn.cmd" : "mvn"));
        return mvn.isFile() ? mvn.getAbsolutePath() : null;
    }

    private static @NotNull String tail(@NotNull String output) {
        String trimmed = output.trim();
        int max = 1500;
        return trimmed.length() <= max ? trimmed : "..." + trimmed.substring(trimmed.length() - max);
    }
}

package com.zukotuutori.mavenassist.maven;

import com.zukotuutori.mavenassist.util.Notifier;
import com.zukotuutori.mavenassist.util.ProjectTrust;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.PathEnvironmentVariableUtil;
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
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Runs "mvn compile" in a background task and reports the result as a notification. */
public final class MavenBuildRunner {

    private static final int TIMEOUT_MS = 10 * 60 * 1000;
    private static final long MAX_DOWNLOAD_BYTES = 64L * 1024 * 1024;
    private static final long MAX_UNPACKED_BYTES = 256L * 1024 * 1024;
    private static final int MAX_ARCHIVE_ENTRIES = 10_000;
    private static final int MAX_PROCESS_OUTPUT_CHARS = 64 * 1024;
    /** Version downloaded from apache.org when no package manager is available. */
    private static final String MAVEN_VERSION = "3.9.16";
    /** Official Apache SHA-512 of apache-maven-3.9.16-bin.zip; the download is rejected on mismatch. */
    private static final String MAVEN_SHA512 =
            "ed41650d42485cfc243fad22158caf9cbb5dc408ce7a09ddb94dd42a019de929c"
            + "a43065bfa450612cf12bf78b5cafa3884b96c090de326ff590448c933454af3";

    private MavenBuildRunner() {
    }

    public static void build(@Nullable Project project, @NotNull File workingDir, @Nullable Runnable onFinishedEdt) {
        run(project, workingDir, new String[]{"compile"}, onFinishedEdt);
    }

    /** Runs "mvn &lt;goals&gt;" in a background task and reports the result as a notification. */
    public static void run(@Nullable Project project, @NotNull File workingDir,
                           @NotNull String[] goals, @Nullable Runnable onFinishedEdt) {
        if (project != null && !ProjectTrust.isTrusted(project)) {
            Notifier.warn(project, "Maven was not run because this project is open in safe mode. "
                    + "Trust the project first if you want to execute its build.");
            if (onFinishedEdt != null) {
                ApplicationManager.getApplication().invokeLater(onFinishedEdt);
            }
            return;
        }
        String goalText = String.join(" ", goals);
        new Task.Backgroundable(project, "Maven Assist: Running mvn " + goalText, true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setText("Running mvn " + goalText + " in " + workingDir.getName() + "...");
                String maven = findMavenExecutable(workingDir);
                if (maven == null) {
                    Notifier.warn(project, "No Maven executable (mvn) was found on the PATH. mvn " + goalText + " was skipped.");
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
                    CommandResult output = runCommand(command, indicator);
                    if (output.timeout()) {
                        Notifier.error(project, "Maven build timed out after 10 minutes.");
                    } else if (output.exitCode() == 0) {
                        Notifier.info(project, "Maven build succeeded (mvn " + goalText + ").");
                    } else {
                        Notifier.error(project, "Maven build failed (exit code " + output.exitCode() + "):\n"
                                + tail(output.output()));
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
        /** Downloaded into &lt;project&gt;/.mavenassist and used for that project only. */
        PROJECT,
        /** Package manager if available, otherwise ~/.mavenassist, for use everywhere. */
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
                        CommandResult output = runCommand(command, indicator);
                        if (output.timeout()) {
                            Notifier.error(project, "Installing Maven timed out after 10 minutes.");
                            return;
                        }
                        if (output.exitCode() != 0) {
                            Notifier.error(project, "Installing Maven failed (exit code " + output.exitCode()
                                    + "):\n" + tail(output.output()));
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
        try {
            downloadVerifyAndUnpack(indicator, baseDir, urls, tempZip);
        } finally {
            // ~9 MB: do not leave it behind when a download, checksum or unpack step throws.
            Files.deleteIfExists(tempZip);
        }
    }

    private static void downloadVerifyAndUnpack(@NotNull ProgressIndicator indicator,
                                                @NotNull File baseDir,
                                                @NotNull String[] urls,
                                                @NotNull Path tempZip) throws Exception {
        Exception lastFailure = null;
        boolean downloaded = false;
        for (String url : urls) {
            indicator.checkCanceled();
            indicator.setText("Downloading " + url + "...");
            try {
                // openStream() alone has no timeouts at all: a mirror that accepts the
                // connection and then stalls would hang this task indefinitely.
                java.net.URLConnection connection = URI.create(url).toURL().openConnection();
                connection.setConnectTimeout(15_000);
                connection.setReadTimeout(60_000);
                long declaredLength = connection.getContentLengthLong();
                if (declaredLength > MAX_DOWNLOAD_BYTES) {
                    throw new IOException("Maven download is unexpectedly large (" + declaredLength + " bytes).");
                }
                try (InputStream in = connection.getInputStream();
                     java.io.OutputStream out = Files.newOutputStream(tempZip,
                             StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                    copyWithLimit(in, out, MAX_DOWNLOAD_BYTES, indicator);
                }
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
            throw new IOException("Downloaded Maven failed its checksum check and was discarded "
                    + "(expected " + MAVEN_SHA512 + ", got " + actual + "). Nothing was installed.");
        }

        indicator.setText("Unpacking Maven...");
        Path requested = baseDir.toPath().toAbsolutePath().normalize();
        Path requestedParent = requested.getParent();
        if (requestedParent == null) {
            throw new IOException("Invalid Maven installation directory.");
        }
        Files.createDirectories(requestedParent);
        Path realParent = requestedParent.toRealPath();
        Path base = realParent.resolve(requested.getFileName());
        if (Files.exists(base, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(base) || !Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Refusing to install through non-directory or symbolic link: " + base);
            }
        } else {
            Files.createDirectory(base);
        }
        if (Files.isSymbolicLink(base)) {
            throw new IOException("Refusing to install through symbolic link: " + base);
        }
        base = base.toRealPath(LinkOption.NOFOLLOW_LINKS);

        // Extract into a fresh directory first. No archive entry can encounter a pre-existing
        // symlink, and a failed/cancelled extraction never leaves a half-installed Maven.
        Path staging = Files.createTempDirectory(base, ".mavenassist-install-");
        try {
            unpackArchive(tempZip, staging, indicator);
            Path unpacked = staging.resolve("apache-maven-" + MAVEN_VERSION);
            Path unpackedMaven = unpacked.resolve("bin")
                    .resolve(SystemInfo.isWindows ? "mvn.cmd" : "mvn");
            if (!Files.isRegularFile(unpackedMaven, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("mvn executable missing after unpacking.");
            }

            Path destination = base.resolve(unpacked.getFileName());
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(destination)) {
                    throw new IOException("Refusing to replace symbolic link: " + destination);
                }
                File existing = managedMavenExecutable(base.toFile());
                if (existing == null) {
                    throw new IOException("An incomplete Maven installation already exists at " + destination);
                }
            } else {
                try {
                    Files.move(unpacked, destination, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(unpacked, destination);
                }
            }

            Path ignore = base.resolve(".gitignore");
            if (!Files.exists(ignore, LinkOption.NOFOLLOW_LINKS)) {
                Files.writeString(ignore, "# Created by Maven Assist.\n*\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            }
            File mvn = managedMavenExecutable(base.toFile());
            if (mvn == null) {
                throw new IOException("mvn executable missing after installation.");
            }
            if (!SystemInfo.isWindows && !mvn.setExecutable(true)) {
                throw new IOException("Could not make Maven executable: " + mvn);
            }
        } finally {
            deleteTree(staging);
        }
    }

    private static void unpackArchive(@NotNull Path archive,
                                      @NotNull Path staging,
                                      @NotNull ProgressIndicator indicator) throws IOException {
        int entries = 0;
        long totalBytes = 0;
        byte[] buffer = new byte[8192];
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                indicator.checkCanceled();
                if (++entries > MAX_ARCHIVE_ENTRIES) {
                    throw new IOException("Maven archive contains too many entries.");
                }
                Path target = staging.resolve(entry.getName()).normalize();
                if (!target.startsWith(staging)) {
                    throw new IOException("Maven archive contains an unsafe path: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (java.io.OutputStream out = Files.newOutputStream(target,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    int read;
                    while ((read = zip.read(buffer)) != -1) {
                        totalBytes += read;
                        if (totalBytes > MAX_UNPACKED_BYTES) {
                            throw new IOException("Maven archive expands beyond the allowed size.");
                        }
                        out.write(buffer, 0, read);
                    }
                }
            }
        }
    }

    private static void copyWithLimit(@NotNull InputStream in,
                                      @NotNull java.io.OutputStream out,
                                      long limit,
                                      @NotNull ProgressIndicator indicator) throws IOException {
        byte[] buffer = new byte[8192];
        long copied = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            indicator.checkCanceled();
            copied += read;
            if (copied > limit) {
                throw new IOException("Maven download exceeds the allowed size.");
            }
            out.write(buffer, 0, read);
        }
    }

    private static void deleteTree(@NotNull Path root) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best-effort cleanup of a plugin-created temporary directory.
                }
            });
        } catch (IOException ignored) {
            // Best-effort cleanup.
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

    /** The mvn executable of the currently pinned Maven in the given base dir, or null. */
    private static @Nullable File managedMavenExecutable(@NotNull File baseDir) {
        Path basePath = baseDir.toPath().toAbsolutePath().normalize();
        if (Files.isSymbolicLink(basePath)
                || !Files.isDirectory(basePath, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        File installation = new File(baseDir, "apache-maven-" + MAVEN_VERSION);
        String binary = SystemInfo.isWindows ? "mvn.cmd" : "mvn";
        File mvn = new File(installation, "bin" + File.separator + binary);
        Path installationPath = installation.toPath();
        Path binPath = installationPath.resolve("bin");
        Path mvnPath = mvn.toPath();
        if (!Files.isSymbolicLink(installationPath)
                && !Files.isSymbolicLink(binPath)
                && !Files.isSymbolicLink(mvnPath)
                && Files.isRegularFile(mvnPath, LinkOption.NOFOLLOW_LINKS)) {
            return mvn;
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
        return null; // Linux package managers need sudo, so no silent install is possible.
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

    private record CommandResult(int exitCode, boolean timeout, String output) {
    }

    /**
     * Runs a child process while retaining only its output tail. This prevents a verbose or
     * hostile wrapper from filling the IDE heap, and cancellation terminates the process tree.
     */
    private static @NotNull CommandResult runCommand(@NotNull GeneralCommandLine command,
                                                     @NotNull ProgressIndicator indicator) throws Exception {
        Process process = command.createProcess();
        BoundedOutput output = new BoundedOutput(MAX_PROCESS_OUTPUT_CHARS);
        Thread stdout = drain(process.getInputStream(), output, "mavenassist-stdout");
        Thread stderr = drain(process.getErrorStream(), output, "mavenassist-stderr");
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS);
        boolean timeout = false;
        try {
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                indicator.checkCanceled();
                if (System.nanoTime() >= deadline) {
                    timeout = true;
                    break;
                }
            }
            if (timeout) {
                destroyProcessTree(process);
            }
            stdout.join(1_000);
            stderr.join(1_000);
            return new CommandResult(timeout ? -1 : process.exitValue(), timeout, output.value());
        } finally {
            if (process.isAlive()) {
                destroyProcessTree(process);
            }
        }
    }

    private static @NotNull Thread drain(@NotNull InputStream stream,
                                         @NotNull BoundedOutput output,
                                         @NotNull String name) {
        Thread thread = new Thread(() -> {
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                char[] buffer = new char[2048];
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    output.append(buffer, read);
                }
            } catch (IOException ignored) {
                // The stream is closed normally when the process exits or is cancelled.
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void destroyProcessTree(@NotNull Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static final class BoundedOutput {
        private final int limit;
        private final StringBuilder text = new StringBuilder();

        private BoundedOutput(int limit) {
            this.limit = limit;
        }

        private synchronized void append(char @NotNull [] chars, int length) {
            text.append(chars, 0, length);
            int excess = text.length() - limit;
            if (excess > 0) {
                text.delete(0, excess);
            }
        }

        private synchronized @NotNull String value() {
            return text.toString();
        }
    }

    private static @NotNull String tail(@NotNull String output) {
        String trimmed = output.trim();
        int max = 1500;
        return trimmed.length() <= max ? trimmed : "..." + trimmed.substring(trimmed.length() - max);
    }
}

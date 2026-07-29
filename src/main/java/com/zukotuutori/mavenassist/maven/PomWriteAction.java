package com.zukotuutori.mavenassist.maven;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs a small pom mutation as one IDE write command.
 *
 * <p>If the pom is open and has unsaved edits, those edits are saved first. The command is
 * serialized on the EDT, which prevents UI actions and background auto-update passes from
 * racing each other or overwriting the editor's document.</p>
 */
public final class PomWriteAction {

    @FunctionalInterface
    public interface Operation {
        void run() throws IOException;
    }

    private PomWriteAction() {
    }

    public static void run(@NotNull Project project,
                           @NotNull File pom,
                           @NotNull String commandName,
                           @NotNull Operation operation) throws IOException {
        AtomicReference<IOException> failure = new AtomicReference<>();
        Runnable task = () -> {
            VirtualFile virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(pom);
            FileDocumentManager documents = FileDocumentManager.getInstance();
            Document document = virtualFile == null ? null : documents.getDocument(virtualFile);
            if (document != null && documents.isDocumentUnsaved(document)) {
                documents.saveDocument(document);
            }
            WriteCommandAction.runWriteCommandAction(project, commandName, null, () -> {
                try {
                    operation.run();
                } catch (IOException e) {
                    failure.set(e);
                }
            });
            if (virtualFile != null) {
                virtualFile.refresh(false, false);
                if (document != null) {
                    documents.reloadFromDisk(document);
                }
            } else {
                LocalFileSystem.getInstance().refreshAndFindFileByIoFile(pom);
            }
        };

        if (ApplicationManager.getApplication().isDispatchThread()) {
            task.run();
        } else {
            ApplicationManager.getApplication().invokeAndWait(task);
        }
        if (failure.get() != null) {
            throw failure.get();
        }
    }
}

package com.zukotuutori.mavenassist.actions;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiFile;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.zukotuutori.mavenassist.maven.PomUtils;
import com.zukotuutori.mavenassist.model.Dependency;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Works out which dependencies an editor action should act on: the one the caret sits in, or
 * every one a selection touches.
 *
 * <p>Only the project's own top-level {@code <dependencies>} block counts, the same scope the
 * dependency table manages. Entries under {@code dependencyManagement}, inside profiles or
 * belonging to a plugin are deliberately left alone, so an action here can never change
 * something the table would not show.</p>
 */
public final class PomDependencySelection {

    private PomDependencySelection() {
    }

    /**
     * The file on disk behind a pom.xml editor, or null when this is not one of the project's own.
     * A pom.xml opened from elsewhere is refused: acting on it would build in that folder, with
     * whatever mvnw it ships, while the trust check only vouches for the open project.
     */
    public static @Nullable File pomFileOf(@Nullable PsiFile file) {
        if (!(file instanceof XmlFile) || file.getVirtualFile() == null) {
            return null;
        }
        if (!"pom.xml".equals(file.getName())) {
            return null;
        }
        if (!ProjectFileIndex.getInstance(file.getProject()).isInContent(file.getVirtualFile())) {
            return null;
        }
        XmlTag root = ((XmlFile) file).getRootTag();
        if (root == null || !"project".equals(root.getName())) {
            return null;
        }
        return new File(file.getVirtualFile().getPath());
    }

    /**
     * The keys of the dependencies the caret sits in, or that the selection touches, in document
     * order. Read from the editor's PSI only, so it is cheap enough for a menu update.
     */
    public static @NotNull List<String> keys(@NotNull Editor editor, @Nullable PsiFile file) {
        if (pomFileOf(file) == null) {
            return List.of();
        }
        XmlTag root = ((XmlFile) file).getRootTag();
        XmlTag dependencies = root == null ? null : root.findFirstSubTag("dependencies");
        if (dependencies == null) {
            return List.of();
        }
        TextRange touched = touchedRange(editor);
        Set<String> keys = new LinkedHashSet<>();
        for (XmlTag tag : dependencies.findSubTags("dependency")) {
            if (intersects(tag.getTextRange(), touched)) {
                String key = keyOf(tag);
                if (key != null) {
                    keys.add(key);
                }
            }
        }
        return new ArrayList<>(keys);
    }

    /**
     * The dependencies behind the given keys, read from the pom on disk with the reader the rest
     * of the plugin uses, so versions from a ${property} resolve exactly as everywhere else.
     * Save the document first; keys only present in unsaved text are not found.
     */
    public static @NotNull List<Dependency> resolve(@NotNull File pom, @NotNull List<String> keys) {
        Map<String, Dependency> byKey = new HashMap<>();
        try {
            for (Dependency dependency : PomUtils.readDependencies(pom)) {
                byKey.put(dependency.getKey(), dependency);
            }
        } catch (IOException e) {
            return List.of();
        }
        List<Dependency> result = new ArrayList<>();
        for (String key : keys) {
            Dependency dependency = byKey.get(key);
            if (dependency != null) {
                result.add(dependency);
            }
        }
        return result;
    }

    /** The selection, or the caret as an empty range when nothing is selected. */
    private static @NotNull TextRange touchedRange(@NotNull Editor editor) {
        if (editor.getSelectionModel().hasSelection()) {
            return new TextRange(editor.getSelectionModel().getSelectionStart(),
                    editor.getSelectionModel().getSelectionEnd());
        }
        int caret = editor.getCaretModel().getOffset();
        return new TextRange(caret, caret);
    }

    /**
     * A block counts as touched when the range reaches into it. A plain caret inside the block
     * counts too, which is why an empty range at the very edge is still treated as a hit.
     */
    private static boolean intersects(@NotNull TextRange block, @NotNull TextRange touched) {
        return touched.getStartOffset() <= block.getEndOffset()
                && touched.getEndOffset() >= block.getStartOffset();
    }

    /** The {@code groupId:artifactId} key of a {@code <dependency>} tag, or null if incomplete. */
    private static @Nullable String keyOf(@NotNull XmlTag dependency) {
        String groupId = subTagText(dependency, "groupId");
        String artifactId = subTagText(dependency, "artifactId");
        if (groupId == null || artifactId == null) {
            return null;
        }
        return new Dependency(groupId, artifactId, null, null,
                subTagText(dependency, "type"), subTagText(dependency, "classifier")).getKey();
    }

    private static @Nullable String subTagText(@NotNull XmlTag parent, @NotNull String name) {
        XmlTag tag = parent.findFirstSubTag(name);
        if (tag == null) {
            return null;
        }
        String text = tag.getValue().getTrimmedText();
        return text.isEmpty() ? null : text;
    }
}

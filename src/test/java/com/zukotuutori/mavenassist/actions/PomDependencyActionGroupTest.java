package com.zukotuutori.mavenassist.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.Separator;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.TestActionEvent;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.zukotuutori.mavenassist.model.Dependency;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Puts the caret or a selection into a real pom.xml editor and checks what the right-click
 * "Maven Assist" menu offers: hidden outside dependency blocks, the single-dependency actions for
 * one block, the bulk actions for several.
 */
public class PomDependencyActionGroupTest extends BasePlatformTestCase {

    private static final String HEAD = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>my-app</artifactId>
                <version>1.0.0</version>
            """;

    private final PomDependencyActionGroup group = new PomDependencyActionGroup();

    public void testCaretInsideABlockOffersTheSingleDependencyMenu() {
        configure(HEAD + """
                    <dependencies>
                        <dependency>
                            <groupId>commons-io</groupId>
                            <artifactId>commons-io</artifactId>
                            <version>2.15.1</version>
                        </dependency>
                        <dependency>
                            <groupId>com.google.guava</groupId>
                            <artifactId>gu<caret>ava</artifactId>
                            <version>33.0.0-jre</version>
                        </dependency>
                    </dependencies>
                </project>
                """);

        assertEquals(List.of("com.google.guava:guava"), keys());
        assertTrue("menu must be visible", visible());
        assertEquals(List.of("Details...", "Update to newest version", "Show all available versions...",
                "Custom version...", "Auto update", "Delete from pom..."), labels());
    }

    public void testCaretOnTheDependencyTagItselfCounts() {
        configure(HEAD + """
                    <dependencies>
                        <<caret>dependency>
                            <groupId>commons-io</groupId>
                            <artifactId>commons-io</artifactId>
                            <version>2.15.1</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        assertEquals(List.of("commons-io:commons-io"), keys());
    }

    public void testFullySelectedBlocksOfferTheBulkMenu() {
        configure(HEAD + """
                    <dependencies>
                        <selection><dependency>
                            <groupId>commons-io</groupId>
                            <artifactId>commons-io</artifactId>
                            <version>2.15.1</version>
                        </dependency>
                        <dependency>
                            <groupId>com.google.guava</groupId>
                            <artifactId>guava</artifactId>
                            <version>33.0.0-jre</version>
                        </dependency></selection>
                        <dependency>
                            <groupId>org.slf4j</groupId>
                            <artifactId>slf4j-api</artifactId>
                            <version>2.0.9</version>
                        </dependency>
                    </dependencies>
                </project>
                """);

        assertEquals(List.of("commons-io:commons-io", "com.google.guava:guava"), keys());
        assertTrue(visible());
        assertEquals(List.of("Update 2 dependencies to the newest version",
                "Turn auto update on for 2 dependencies",
                "Turn auto update off for 2 dependencies",
                "Delete 2 dependencies from pom..."), labels());
    }

    /** A sloppy selection that only reaches into the outer blocks still takes them along. */
    public void testPartiallySelectedBlocksCountToo() {
        configure(HEAD + """
                    <dependencies>
                        <dependency>
                            <groupId>commons-io</groupId>
                            <artifactId>commons-<selection>io</artifactId>
                            <version>2.15.1</version>
                        </dependency>
                        <dependency>
                            <groupId>com.google.guava</groupId>
                            <artifactId>guava</artifactId>
                            <version>33.0.0-jre</version>
                        </dependency>
                        <dependency>
                            <groupId>org.slf4j</groupId>
                            <artifactId>slf4j</selection>-api</artifactId>
                            <version>2.0.9</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        assertEquals(List.of("commons-io:commons-io", "com.google.guava:guava", "org.slf4j:slf4j-api"),
                keys());
    }

    public void testCaretOutsideTheDependenciesHidesTheMenu() {
        configure(HEAD + """
                    <properties>
                        <java.<caret>version>21</java.version>
                    </properties>
                    <dependencies>
                        <dependency>
                            <groupId>commons-io</groupId>
                            <artifactId>commons-io</artifactId>
                            <version>2.15.1</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        assertTrue(keys().isEmpty());
        assertFalse("menu must stay hidden outside dependency blocks", visible());
    }

    /** dependencyManagement is not what the table manages, so the editor leaves it alone too. */
    public void testDependencyManagementIsIgnored() {
        configure(HEAD + """
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-<caret>dependencies</artifactId>
                                <version>3.3.0</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """);
        assertTrue(keys().isEmpty());
        assertFalse(visible());
    }

    public void testOtherXmlFilesAreIgnored() {
        myFixture.configureByText("settings.xml", HEAD + """
                    <dependencies>
                        <dependency>
                            <groupId>commons-io</groupId>
                            <artifactId>commons-<caret>io</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """);
        assertTrue(keys().isEmpty());
        assertFalse(visible());
    }

    public void testTypeAndClassifierKeepTheirOwnKey() {
        configure(HEAD + """
                    <dependencies>
                        <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>lib</artifactId>
                            <version>1.0</version>
                            <classifier>tests</classifier>
                            <type>test-jar</type><caret>
                        </dependency>
                    </dependencies>
                </project>
                """);
        assertEquals(List.of("com.example:lib:test-jar:tests"), keys());
    }

    /** Resolving reads the pom on disk, so a ${property} version comes back already resolved. */
    public void testResolveReadsResolvedVersionsFromDisk() throws Exception {
        File pom = Files.createTempDirectory("maven-assist-editor").resolve("pom.xml").toFile();
        Files.writeString(pom.toPath(), HEAD + """
                    <properties>
                        <guava.version>33.0.0-jre</guava.version>
                    </properties>
                    <dependencies>
                        <dependency>
                            <groupId>com.google.guava</groupId>
                            <artifactId>guava</artifactId>
                            <version>${guava.version}</version>
                        </dependency>
                    </dependencies>
                </project>
                """);

        List<Dependency> resolved =
                PomDependencySelection.resolve(pom, List.of("com.google.guava:guava", "missing:one"));
        assertEquals(1, resolved.size());
        assertEquals("33.0.0-jre", resolved.get(0).getVersion());
    }

    /**
     * A pom.xml opened from outside the project, a download for example, gets no menu: acting on
     * it would build in that foreign folder, with its own mvnw, under this project's trust.
     */
    public void testPomOutsideTheProjectIsIgnored() throws Exception {
        File pom = new File(FileUtil.createTempDirectory("maven-assist-foreign", null), "pom.xml");
        Files.writeString(pom.toPath(), HEAD + """
                    <dependencies>
                        <dependency>
                            <groupId>commons-io</groupId>
                            <artifactId>commons-io</artifactId>
                            <version>2.15.1</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(pom);
        assertNotNull(file);
        myFixture.configureFromExistingVirtualFile(file);
        myFixture.getEditor().getCaretModel().moveToOffset(
                myFixture.getEditor().getDocument().getText().indexOf("commons-io"));

        assertNull(PomDependencySelection.pomFileOf(myFixture.getFile()));
        assertTrue(keys().isEmpty());
        assertFalse("menu must stay hidden for a pom outside the project", visible());
    }

    // ---------------------------------------------------------------- helpers

    private void configure(@NotNull String text) {
        myFixture.configureByText("pom.xml", text);
    }

    private @NotNull List<String> keys() {
        return PomDependencySelection.keys(myFixture.getEditor(), myFixture.getFile());
    }

    private @NotNull AnActionEvent event() {
        return TestActionEvent.createTestEvent(group, SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, getProject())
                .add(CommonDataKeys.EDITOR, myFixture.getEditor())
                .add(CommonDataKeys.PSI_FILE, myFixture.getFile())
                .build());
    }

    private boolean visible() {
        AnActionEvent e = event();
        group.update(e);
        return e.getPresentation().isEnabledAndVisible();
    }

    private @NotNull List<String> labels() {
        List<String> labels = new ArrayList<>();
        for (AnAction child : group.getChildren(event())) {
            if (!(child instanceof Separator)) {
                labels.add(child.getTemplatePresentation().getText());
            }
        }
        return labels;
    }
}

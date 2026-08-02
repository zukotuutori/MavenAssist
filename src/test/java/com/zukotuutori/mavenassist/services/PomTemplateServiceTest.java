package com.zukotuutori.mavenassist.services;

import com.zukotuutori.mavenassist.maven.PomUtils;
import com.zukotuutori.mavenassist.model.Dependency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PomTemplateServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void snapshotKeepsNewestAndPinnedChoicesPerDependency() {
        List<Dependency> dependencies = List.of(
                new Dependency("example", "newest", "1.0.0"),
                new Dependency("example", "pinned", "2.0.0"));

        PomTemplateService.Template template = PomTemplateService.snapshot(
                "  My Template  ", "  com.example  ", " app ", " 1.0.0 ", "",
                dependencies, Map.of("example:pinned", "2.5.1"));

        assertEquals("My Template", template.name);
        assertEquals("com.example", template.groupId);
        assertEquals("app", template.artifactId);
        assertFalse(template.useNewestVersions);
        assertEquals(List.of("example:newest:", "example:pinned:2.5.1"), template.dependencies);
    }

    @Test
    void snapshotPomReadsCurrentCoordinatesParentAndDependencyVersions() throws Exception {
        Path parent = tempDir.resolve("parent.xml");
        Files.writeString(parent, """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>3.0.0</version>
                    <packaging>pom</packaging>
                </project>
                """);
        Path pom = tempDir.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>parent</artifactId>
                        <version>3.0.0</version>
                        <relativePath>parent.xml</relativePath>
                    </parent>
                    <artifactId>child</artifactId>
                    <properties>
                        <library.version>4.2.0</library.version>
                    </properties>
                    <dependencies>
                        <dependency>
                            <groupId>example</groupId>
                            <artifactId>library</artifactId>
                            <version>${library.version}</version>
                        </dependency>
                    </dependencies>
                </project>
                """);

        PomTemplateService.Template template =
                PomTemplateService.snapshotPom("Existing Project", pom.toFile());

        assertEquals("Existing Project", template.name);
        assertEquals("com.example", template.groupId);
        assertEquals("child", template.artifactId);
        assertEquals("3.0.0", template.version);
        assertEquals(parent.toFile().getCanonicalPath(), template.parentPomPath);
        assertFalse(template.useNewestVersions);
        assertEquals(List.of("example:library:4.2.0"), template.dependencies);
    }

    @Test
    void importsPomWithParentAndExtendedDependencyIdentity() throws Exception {
        Path pom = tempDir.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>org.example</groupId>
                        <artifactId>parent</artifactId>
                        <version>4.0.0</version>
                        <relativePath/>
                    </parent>
                    <groupId>com.example</groupId>
                    <artifactId>app</artifactId>
                    <version>1.2.3</version>
                    <dependencies>
                        <dependency>
                            <groupId>example</groupId>
                            <artifactId>newest</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>example</groupId>
                            <artifactId>fixtures</artifactId>
                            <version>2.5.1</version>
                            <type>test-jar</type>
                            <classifier>tests</classifier>
                        </dependency>
                    </dependencies>
                </project>
                """);

        PomTemplateService.Template imported =
                PomTemplateService.importPom("Imported", pom.toFile());
        assertEquals("com.example", imported.groupId);
        assertEquals("app", imported.artifactId);
        assertEquals("1.2.3", imported.version);
        assertEquals("org.example:parent:4.0.0",
                PomTemplateService.parentReference(imported));
        assertEquals(List.of(
                "example:newest:",
                "example:fixtures:test-jar:tests:2.5.1"), imported.dependencies);
    }

    @Test
    void exportsCurrentPomAsAnExactCopyAndReplacesConfirmedTarget() throws Exception {
        Path currentDirectory = Files.createDirectory(tempDir.resolve("current"));
        Path exportDirectory = Files.createDirectory(tempDir.resolve("export"));
        Path source = currentDirectory.resolve("pom.xml");
        Path target = exportDirectory.resolve("pom.xml");
        byte[] pomBytes = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!-- Preserve this comment and formatting exactly. -->
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>example</groupId>
                  <artifactId>exact-copy</artifactId>
                  <version>1.0.0</version>
                </project>
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(source, pomBytes);
        Files.writeString(target, "old content");

        PomUtils.copyPom(source.toFile(), target.toFile());

        assertArrayEquals(pomBytes, Files.readAllBytes(target));
        assertThrows(IOException.class, () -> PomUtils.copyPom(source.toFile(), source.toFile()));
    }

    @Test
    void importRejectsPomLargerThanFiveMegabytesBeforeParsing() throws Exception {
        Path oversized = tempDir.resolve("oversized.xml");
        Files.write(oversized, new byte[5 * 1024 * 1024 + 1]);

        IOException error = assertThrows(IOException.class,
                () -> PomTemplateService.importPom("Too Large", oversized.toFile()));

        assertTrue(error.getMessage().contains("5 MB"));
    }
}

package com.zukotuutori.mavenassist.maven;

import com.zukotuutori.mavenassist.model.Dependency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PomUtilsTest {

    @TempDir
    Path temp;

    @Test
    void classifierVariantsAreUpdatedAndRemovedIndependently() throws Exception {
        File pom = pom("""
                <dependencies>
                    <dependency>
                        <groupId>example</groupId><artifactId>library</artifactId>
                        <version>1.0.0</version><classifier>linux</classifier>
                    </dependency>
                    <dependency>
                        <groupId>example</groupId><artifactId>library</artifactId>
                        <version>2.0.0</version><classifier>windows</classifier>
                    </dependency>
                </dependencies>
                """);

        List<Dependency> dependencies = PomUtils.readDependencies(pom);
        assertEquals(List.of("example:library:jar:linux", "example:library:jar:windows"),
                dependencies.stream().map(Dependency::getKey).toList());

        PomUtils.setDependencyVersion(pom, dependencies.get(1), "2.1.0");
        List<Dependency> updated = PomUtils.readDependencies(pom);
        assertEquals("1.0.0", updated.get(0).getVersion());
        assertEquals("2.1.0", updated.get(1).getVersion());

        PomUtils.removeDependency(pom, updated.get(0));
        List<Dependency> remaining = PomUtils.readDependencies(pom);
        assertEquals(1, remaining.size());
        assertEquals("windows", remaining.getFirst().getClassifier());
    }

    @Test
    void updatingSharedPropertyChangesOnlySelectedDependency() throws Exception {
        File pom = pom("""
                <properties><shared.version>1.0.0</shared.version></properties>
                <dependencies>
                    <dependency>
                        <groupId>example</groupId><artifactId>first</artifactId>
                        <version>${shared.version}</version>
                    </dependency>
                    <dependency>
                        <groupId>example</groupId><artifactId>second</artifactId>
                        <version>${shared.version}</version>
                    </dependency>
                </dependencies>
                """);

        Dependency first = PomUtils.readDependencies(pom).getFirst();
        PomUtils.setDependencyVersion(pom, first, "9.9.9");

        List<Dependency> result = PomUtils.readDependencies(pom);
        assertEquals("9.9.9", result.get(0).getVersion());
        assertEquals("1.0.0", result.get(1).getVersion());
        assertTrue(Files.readString(pom.toPath()).contains("<shared.version>1.0.0</shared.version>"));
    }

    @Test
    void refusesToApplyAStaleBackgroundUpdate() throws Exception {
        File pom = pom("""
                <dependencies><dependency>
                    <groupId>example</groupId><artifactId>library</artifactId><version>1.0.0</version>
                </dependency></dependencies>
                """);
        Dependency stale = PomUtils.readDependencies(pom).getFirst();
        Files.writeString(pom.toPath(), Files.readString(pom.toPath())
                .replace("<version>1.0.0</version>", "<version>1.5.0</version>"));

        assertThrows(IOException.class, () -> PomUtils.setDependencyVersion(pom, stale, "2.0.0"));
        assertEquals("1.5.0", PomUtils.readDependencies(pom).getFirst().getVersion());
    }

    @Test
    void createPomNeverOverwritesAnExistingFile() throws Exception {
        File existing = temp.resolve("pom.xml").toFile();
        Files.writeString(existing.toPath(), "keep me");

        assertThrows(IOException.class, () -> PomUtils.createPom(existing,
                "example", "app", "1.0.0", null, null, List.of()));
        assertEquals("keep me", Files.readString(existing.toPath()));
    }

    @Test
    void directDependencyEditDoesNotTouchPluginDependency() throws Exception {
        File pom = pom("""
                <build><plugins><plugin><dependencies>
                    <dependency><groupId>example</groupId><artifactId>library</artifactId>
                        <version>plugin</version></dependency>
                </dependencies></plugin></plugins></build>
                <dependencies>
                    <dependency><groupId>example</groupId><artifactId>library</artifactId>
                        <version>project</version></dependency>
                </dependencies>
                """);

        Dependency dependency = PomUtils.readDependencies(pom).getFirst();
        PomUtils.setDependencyVersion(pom, dependency, "updated");
        String text = Files.readString(pom.toPath());
        assertTrue(text.contains("<version>plugin</version>"));
        assertTrue(text.contains("<version>updated</version>"));
    }

    @Test
    void readsAndEditsNamespacePrefixedPomElements() throws Exception {
        File pom = temp.resolve("pom.xml").toFile();
        Files.writeString(pom.toPath(), """
                <m:project xmlns:m="http://maven.apache.org/POM/4.0.0">
                  <m:modelVersion>4.0.0</m:modelVersion>
                  <m:groupId>example</m:groupId><m:artifactId>app</m:artifactId><m:version>1</m:version>
                  <m:dependencies>
                    <m:dependency data-source="test">
                      <m:groupId>example</m:groupId><m:artifactId>library</m:artifactId>
                      <m:version>1.0.0</m:version>
                    </m:dependency>
                  </m:dependencies>
                </m:project>
                """);

        Dependency dependency = PomUtils.readDependencies(pom).getFirst();
        PomUtils.setDependencyVersion(pom, dependency, "1.1.0");
        assertEquals("1.1.0", PomUtils.readDependencies(pom).getFirst().getVersion());
        assertTrue(Files.readString(pom.toPath()).contains("<m:version>1.1.0</m:version>"));
    }

    @Test
    void dependencyEditsIgnoreXmlLookingTextInCommentsAndCdata() throws Exception {
        File pom = pom("""
                <!--
                    <dependency><groupId>example</groupId><artifactId>library</artifactId>
                        <version>commented</version></dependency>
                -->
                <description><![CDATA[
                    <dependency><groupId>example</groupId><artifactId>library</artifactId>
                        <version>cdata</version></dependency>
                ]]></description>
                <dependencies>
                    <dependency><groupId>example</groupId><artifactId>library</artifactId>
                        <version>real</version></dependency>
                </dependencies>
                """);

        Dependency dependency = PomUtils.readDependencies(pom).getFirst();
        PomUtils.setDependencyVersion(pom, dependency, "updated");

        String text = Files.readString(pom.toPath());
        assertTrue(text.contains("<version>commented</version>"));
        assertTrue(text.contains("<version>cdata</version>"));
        assertEquals("updated", PomUtils.readDependencies(pom).getFirst().getVersion());
    }

    @Test
    void parentReplacementIgnoresCommentedParent() throws Exception {
        File pom = pom("""
                <!-- <parent><groupId>old</groupId><artifactId>old</artifactId>
                     <version>old</version></parent> -->
                """);

        PomUtils.setParentPom(pom, new PomUtils.PomInfo("new.group", "new-parent", "2.0.0"), null);

        String text = Files.readString(pom.toPath());
        assertTrue(text.contains("<groupId>old</groupId>"));
        assertEquals(new PomUtils.PomInfo("new.group", "new-parent", "2.0.0"),
                PomUtils.readParentInfo(pom));
    }

    @Test
    void dependencyInsertionIgnoresCommentedClosingTag() throws Exception {
        File pom = pom("""
                <dependencies>
                    <!-- </dependencies> -->
                    <dependency><groupId>example</groupId><artifactId>existing</artifactId>
                        <version>1</version></dependency>
                </dependencies>
                """);

        PomUtils.addDependencies(pom, List.of(new Dependency("example", "added", "2")));

        assertEquals(List.of("existing", "added"),
                PomUtils.readDependencies(pom).stream().map(Dependency::getArtifactId).toList());
    }

    @Test
    void rejectsDoctypeAndExternalEntities() throws Exception {
        Path secret = temp.resolve("secret.txt");
        Files.writeString(secret, "do-not-read");
        File pom = temp.resolve("pom.xml").toFile();
        Files.writeString(pom.toPath(), """
                <?xml version="1.0"?>
                <!DOCTYPE project [<!ENTITY xxe SYSTEM "%s">]>
                <project><modelVersion>4.0.0</modelVersion>
                <groupId>example</groupId><artifactId>app</artifactId><version>&xxe;</version></project>
                """.formatted(secret.toUri()));

        IOException error = assertThrows(IOException.class, () -> PomUtils.readPomInfo(pom));
        assertFalse(error.getMessage().contains("do-not-read"));
    }

    private File pom(String body) throws IOException {
        File pom = temp.resolve("pom.xml").toFile();
        Files.writeString(pom.toPath(), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>example</groupId>
                    <artifactId>app</artifactId>
                    <version>1.0.0</version>
                %s
                </project>
                """.formatted(body));
        return pom;
    }
}

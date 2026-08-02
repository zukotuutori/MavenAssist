package com.zukotuutori.mavenassist.maven;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MavenCentralClientTest {

    @Test
    void readsCurrentStableReleaseInsteadOfOldSearchIndexResult() throws Exception {
        String metadata = """
                <metadata>
                  <groupId>com.squareup.okhttp3</groupId>
                  <artifactId>okhttp</artifactId>
                  <versioning>
                    <latest>5.4.0</latest>
                    <release>5.4.0</release>
                    <versions>
                      <version>4.12.0</version>
                      <version>5.0.0-alpha.16</version>
                      <version>5.0.0</version>
                      <version>5.4.0</version>
                    </versions>
                  </versioning>
                </metadata>
                """;

        assertEquals("5.4.0", MavenCentralClient.parseLatestVersion(bytes(metadata)));
    }

    @Test
    void skipsPreReleaseReleaseAndUsesNewestStableVersion() throws Exception {
        String metadata = """
                <metadata>
                  <versioning>
                    <latest>3.0.0-beta3</latest>
                    <release>3.0.0-beta3</release>
                    <versions>
                      <version>2.25.3</version>
                      <version>2.25.4</version>
                      <version>3.0.0-alpha1</version>
                      <version>3.0.0-beta3</version>
                    </versions>
                  </versioning>
                </metadata>
                """;

        assertEquals("2.25.4", MavenCentralClient.parseLatestVersion(bytes(metadata)));
    }

    @Test
    void rejectsUnsafeReturnedVersions() throws Exception {
        String metadata = """
                <metadata>
                  <versioning>
                    <release>5.4.0/../../other</release>
                    <versions>
                      <version>4.12.0</version>
                      <version>5.4.0/../../other</version>
                    </versions>
                  </versioning>
                </metadata>
                """;

        assertEquals("4.12.0", MavenCentralClient.parseLatestVersion(bytes(metadata)));
    }

    @Test
    void rejectsDoctypeMetadata() {
        String metadata = """
                <!DOCTYPE metadata [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <metadata><versioning><release>&xxe;</release></versioning></metadata>
                """;

        assertThrows(IOException.class,
                () -> MavenCentralClient.parseLatestVersion(bytes(metadata)));
    }

    @Test
    void returnsNullWhenMetadataHasNoStableVersion() throws Exception {
        String metadata = """
                <metadata>
                  <versioning>
                    <release>2.0.0-RC1</release>
                    <versions>
                      <version>2.0.0-alpha1</version>
                      <version>2.0.0-RC1</version>
                    </versions>
                  </versioning>
                </metadata>
                """;

        assertNull(MavenCentralClient.parseLatestVersion(bytes(metadata)));
    }

    @Test
    void listsEveryPublishedVersionIncludingPreReleases() throws Exception {
        String metadata = """
                <metadata>
                  <versioning>
                    <release>5.4.0</release>
                    <versions>
                      <version>4.12.0</version>
                      <version>5.0.0-alpha.16</version>
                      <version>5.4.0</version>
                    </versions>
                  </versioning>
                </metadata>
                """;

        assertEquals(List.of("4.12.0", "5.0.0-alpha.16", "5.4.0"),
                MavenCentralClient.parseVersions(bytes(metadata)));
    }

    @Test
    void listsNoVersionsWhenMetadataHasNoVersionsElement() throws Exception {
        String metadata = "<metadata><versioning><release>1.0.0</release></versioning></metadata>";

        assertEquals(List.of(), MavenCentralClient.parseVersions(bytes(metadata)));
    }

    @Test
    void buildsMetadataUrlFromExactCoordinates() {
        assertEquals(
                "https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp/maven-metadata.xml",
                MavenCentralClient.metadataUri("com.squareup.okhttp3", "okhttp").toString());
        assertThrows(IllegalArgumentException.class,
                () -> MavenCentralClient.metadataUri("../private", "artifact"));
        assertThrows(IllegalArgumentException.class,
                () -> MavenCentralClient.metadataUri("com.example", ".."));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}

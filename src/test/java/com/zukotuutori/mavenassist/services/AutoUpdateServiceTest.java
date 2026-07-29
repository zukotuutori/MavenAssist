package com.zukotuutori.mavenassist.services;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoUpdateServiceTest {

    @Test
    void usesRelativeProjectPathsAndHashesExternalPaths() {
        String project = new File("/workspace/example").getAbsolutePath();
        assertEquals("module/pom.xml",
                AutoUpdateService.pomKey(project, new File(project, "module/pom.xml")));

        String external = AutoUpdateService.pomKey(project, new File("/Users/private-name/project/pom.xml"));
        assertTrue(external.startsWith("external:"));
        assertFalse(external.contains("private-name"));
        assertEquals(73, external.length());
    }
}

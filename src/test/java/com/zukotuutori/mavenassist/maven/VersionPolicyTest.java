package com.zukotuutori.mavenassist.maven;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionPolicyTest {

    @Test
    void rejectsPreReleaseCandidates() {
        assertFalse(VersionPolicy.isStable("3.0.0-beta3"));
        assertFalse(VersionPolicy.isStable("2.0.0-RC1"));
        assertFalse(VersionPolicy.isStable("1.0.0-SNAPSHOT"));
        assertFalse(VersionPolicy.isStable("4.0.0-dev12"));
        assertFalse(VersionPolicy.isStable("4.0.0-nightly"));
        assertFalse(VersionPolicy.isStable("4.0.0-canary.2"));
        assertFalse(VersionPolicy.isStable("4.0.0-pre1"));
        assertTrue(VersionPolicy.isStable("6.5.2.Final"));
        assertTrue(VersionPolicy.isStable("33.4.8-jre"));
    }

    @Test
    void acceptsOnlyStrictStableUpgrades() {
        assertTrue(VersionPolicy.isUpgrade("1.9.0", "1.10.0"));
        assertTrue(VersionPolicy.isUpgrade("1.0.0-rc1", "1.0.0"));
        assertFalse(VersionPolicy.isUpgrade("2.0.0", "1.9.9"));
        assertFalse(VersionPolicy.isUpgrade("2.0.0", "3.0.0-beta1"));
        assertFalse(VersionPolicy.isUpgrade("", "3.0.0"));
        assertFalse(VersionPolicy.isUpgrade("release-a", "release-b"));
        assertEquals("2.0.0", VersionPolicy.newestOrFallback("2.0.0", "1.9.0"));
        assertEquals("2.1.0", VersionPolicy.newestOrFallback("2.0.0", "2.1.0"));
        assertEquals("2.0.0", VersionPolicy.newestOrFallback("2.0.0", "3.0.0-beta1"));
    }
}

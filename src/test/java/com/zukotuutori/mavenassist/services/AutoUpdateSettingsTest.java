package com.zukotuutori.mavenassist.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoUpdateSettingsTest {

    @Test
    void confirmsNonMajorUpdatesByDefaultAndPersistsThePreference() {
        AutoUpdateSettings settings = new AutoUpdateSettings();
        assertTrue(settings.isConfirmNonMajorUpdates());

        settings.setConfirmNonMajorUpdates(false);
        assertFalse(settings.isConfirmNonMajorUpdates());

        AutoUpdateSettings restored = new AutoUpdateSettings();
        restored.loadState(settings.getState());
        assertFalse(restored.isConfirmNonMajorUpdates());
    }

    @Test
    void disablingOptionalDialogsNeverDisablesMajorConfirmation() {
        assertFalse(AutoUpdateRunner.requiresConfirmation("2.1.0", "2.2.0", false));
        assertTrue(AutoUpdateRunner.requiresConfirmation("2.1.0", "2.2.0", true));
        assertTrue(AutoUpdateRunner.requiresConfirmation("2.9.0", "3.0.0", false));
        assertTrue(AutoUpdateRunner.requiresConfirmation("release-a", "release-b", false));
    }
}

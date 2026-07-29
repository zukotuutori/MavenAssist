package com.zukotuutori.mavenassist.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiTextTest {

    @Test
    void rendersSwingHtmlPayloadAsText() {
        String rendered = UiText.html("<html><img src=\"https://example.invalid/pixel\">");

        assertTrue(rendered.startsWith("<html>&lt;html&gt;&lt;img"));
        assertFalse(rendered.contains("<img"));
    }
}

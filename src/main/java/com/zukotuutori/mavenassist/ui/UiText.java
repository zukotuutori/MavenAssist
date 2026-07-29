package com.zukotuutori.mavenassist.ui;

import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.NotNull;

/** Renders arbitrary POM/catalog values as text even in Swing components that understand HTML. */
final class UiText {

    private UiText() {
    }

    static @NotNull String html(@NotNull Object value) {
        return "<html>" + StringUtil.escapeXmlEntities(String.valueOf(value)) + "</html>";
    }
}

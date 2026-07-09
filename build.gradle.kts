plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.17.0"
}

group = "com.zukotuutori"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Resolve the IntelliJ Platform SDK from the marketplace so anyone can build.
        // To build against a locally installed IDE instead, replace with:
        //   local("/Applications/IntelliJ IDEA.app")
        create("IC", "2025.2")
    }
}

intellijPlatform {
    buildSearchableOptions = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "251"
            untilBuild = provider { null }
        }
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

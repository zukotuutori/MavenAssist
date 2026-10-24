plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.zukotuutori"
version = "2.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
    testRuntimeOnly("junit:junit:4.13.2")

    intellijPlatform {
        // Resolve the IntelliJ Platform SDK from the marketplace so anyone can build.
        // To build against a locally installed IDE instead, replace with:
        //   local("/Applications/IntelliJ IDEA.app")
        create("IC", "2025.1")
    }
}

intellijPlatform {
    buildSearchableOptions = false

    pluginVerification {
        ides {
            // The compile target is also the declared minimum supported IDE.
            current()
        }
    }

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

tasks.test {
    useJUnitPlatform()
}

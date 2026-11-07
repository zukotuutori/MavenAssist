import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.zukotuutori"
version = "2.1.2"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
    // The platform's own test fixtures are JUnit 3/4 based, so the vintage engine runs them
    // next to the plain JUnit 5 tests.
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:6.1.3")

    intellijPlatform {
        // Resolve the IntelliJ Platform SDK from the marketplace so anyone can build.
        // To build against a locally installed IDE instead, replace with:
        //   local("/Applications/IntelliJ IDEA.app")
        create("IC", "2025.1")
        testFramework(TestFrameworkType.Platform)
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

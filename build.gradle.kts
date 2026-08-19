import org.jetbrains.intellij.platform.gradle.TestFrameworkType

// Largely based on intellij-platform-plugin-template
// https://github.com/JetBrains/intellij-platform-plugin-template/blob/main/build.gradle.kts

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
    id("com.github.ben-manes.versions")
}

// TODO:
// The plugin template was greatly simplified by JetBrains.
// It seems like the new template has defaults that remove the need for some of this code.
// As it is, the plugin builds and work, and tests pass. But the changelog functionality,
// extracting the description from the README, signing and publishing need all to be tested.
// It's possible that some of the commented out code must be brought back.
// Once tested, the unused parts can be deleted.

dependencies {
    testImplementation("junit:junit:4.13.2")

    implementation(libs.openrndr.color) { isTransitive = false }
    implementation(libs.openrndr.math) { isTransitive = false }
    implementation(libs.orx.color) { isTransitive = false }

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2025.2.6.2")

        // 2025.2 bundles Kotlin 2.2, whose plugin can deserialize the openrndr 0.4.5 jars' Kotlin 2.2.0
        // metadata. Older platforms (2024.2.5 bundled kotlinc 1.9.24) cannot read it, so the IDE builds no
        // symbols for ColorRGBa & friends, and every gutter/completion resolution silently fails.
        //intellijIdeaCommunity("2025.2")

        // Test framework required for BasePlatformTestCase/myFixture (IntelliJ Platform Gradle Pluemgin 2.x).
        // The Java framework supplies IdeaTestUtil and DefaultLightProjectDescriptor used by the tests.
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)

        bundledPlugin("org.jetbrains.kotlin")
    }
}

tasks.test {
    // The IntelliJ light-fixture tests (which boot a real in-memory IDE with indexing) are memory hungry;
    // 2g avoids OOM-kills (exit 137) and GC-thrash that otherwise surface as flaky "Too long completion" errors.
    maxHeapSize = "2g"
}

tasks {
    dependencyUpdates {
        gradleReleaseChannel = "current"

        val nonStableKeywords = listOf("alpha", "beta", "rc")

        fun isNonStable(version: String) = nonStableKeywords.any {
            version.lowercase().contains(it)
        }

        rejectVersionIf {
            isNonStable(candidate.version) && !isNonStable(currentVersion)
        }
    }
}

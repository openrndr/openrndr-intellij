import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

// Largely based on intellij-platform-plugin-template
// https://github.com/JetBrains/intellij-platform-plugin-template/blob/main/build.gradle.kts

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
    alias(libs.plugins.versions)
}

// TODO: Test signing and publishing.

dependencies {
    testImplementation(libs.junit)

    implementation(libs.openrndr.color) { isTransitive = false }
    implementation(libs.openrndr.math) { isTransitive = false }
    implementation(libs.orx.color) { isTransitive = false }

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea(libs.versions.intellijIdea.get())

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

tasks.named<DependencyUpdatesTask>("dependencyUpdates") {
    gradleReleaseChannel = "current"

    val nonStableKeywords = listOf("alpha", "beta", "rc", "dev")

    fun isNonStable(version: String) = nonStableKeywords.any {
        version.lowercase().contains(it)
    }

    rejectVersionIf {
        isNonStable(candidate.version) && !isNonStable(currentVersion)
    }
}

intellijPlatform {
    pluginVerification {
        freeArgs = listOf(
            "-mute",
            "TemplateWordInPluginId,ForbiddenPluginIdPrefix"
        )
    }
}
import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

// Largely based on intellij-platform-plugin-template
// https://github.com/JetBrains/intellij-platform-plugin-template/blob/main/build.gradle.kts

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
    alias(libs.plugins.versions)
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.opentest4j)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea(libs.versions.intellijIdea.get())
        bundledPlugin("com.intellij.java")
        // Optional dependencies (see plugin.xml): the Kotlin features and the guide's browser tab
        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("com.intellij.modules.jcef")

        // BasePlatformTestCase / myFixture, and the Java fixtures (DefaultLightProjectDescriptor) the tests use
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)
    }
}

// The OPENRNDR and orx jars, with sources for the KDoc checks, that the completion tests put in their test project.
// The tests find them in the Gradle cache and skip themselves without them; resolving them here puts them there,
// also on CI.
val testProjectLibraries: Configuration by configurations.creating {
    isTransitive = false
}

dependencies {
    listOf(
        "org.openrndr:openrndr-application-jvm:0.5.0",
        "org.openrndr:openrndr-color-jvm:0.5.0",
        "org.openrndr:openrndr-draw-jvm:0.5.0",
        "org.openrndr:openrndr-event-jvm:0.5.0",
        "org.openrndr:openrndr-math-jvm:0.5.0",
        "org.openrndr:openrndr-platform-jvm:0.5.0",
        "org.openrndr:openrndr-shape-jvm:0.5.0",
        "org.openrndr:openrndr-utils-jvm:0.5.0",
        "org.openrndr.extra:orx-color-jvm:0.5.0",
        "org.openrndr.extra:orx-compositor-jvm:0.5.0",
        "org.openrndr.extra:orx-fx-jvm:0.5.0",
        "org.openrndr.extra:orx-parameters-jvm:0.5.0",
        "org.openrndr.extra:orx-shader-phrases-jvm:0.5.0",
        "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0",
        "org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.11.0",
        "io.github.oshai:kotlin-logging-jvm:8.0.4",
    ).forEach { library ->
        testProjectLibraries(library)
        testProjectLibraries("$library:sources")
    }
}

kotlin {
    // IntelliJ IDEA 2026.2 runs on, and is compiled for, Java 25
    jvmToolchain(25)
}

tasks.test {
    // Run the Kotlin plugin in K2 mode, the default in the IDE
    systemProperty("idea.kotlin.plugin.use.k2", "true")
    // The light-fixture tests boot an in-memory IDE with indexing, and are memory hungry
    maxHeapSize = "2g"
    // Downloads the test project's libraries first
    inputs.files(testProjectLibraries)
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
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
            untilBuild = provider { null } // no upper bound
        }

        // The Marketplace "What's New": this version's changelog section, or Unreleased while it has none
        changeNotes = provider {
            with(changelog) {
                renderItem(
                    (getOrNull(project.version.toString()) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }
    }

    // The secrets the Release workflow passes on: https://plugins.jetbrains.com/docs/intellij/plugin-signing.html
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    pluginVerification {
        ides {
            recommended()
        }
        // Fails on what breaks the plugin in an IDE, not on the one known internal API: the completion section
        // headings need SeparatorLookupElement, since the public grouped completion drops its headings
        failureLevel = listOf(
            VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
            VerifyPluginTask.FailureLevel.SCHEDULED_FOR_REMOVAL_API_USAGES,
        )
        freeArgs = listOf(
            "-mute",
            "TemplateWordInPluginId,ForbiddenPluginIdPrefix"
        )
    }
}

changelog {
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
}

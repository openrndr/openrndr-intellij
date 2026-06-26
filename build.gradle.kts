import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML

// Largely based on intellij-platform-plugin-template
// https://github.com/JetBrains/intellij-platform-plugin-template/blob/main/build.gradle.kts

plugins {
    id("java")
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

//group = providers.gradleProperty("pluginGroup").get()
//version = providers.gradleProperty("pluginVersion").get()

dependencies {
    testImplementation("junit:junit:4.13.2")

    implementation(libs.openrndr.color) { isTransitive = false }
    implementation(libs.openrndr.math) { isTransitive = false }
    implementation(libs.orx.color) { isTransitive = false }

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        // 2025.2 bundles Kotlin 2.2, whose plugin can deserialize the openrndr 0.4.5 jars' Kotlin 2.2.0
        // metadata. Older platforms (2024.2.5 bundled kotlinc 1.9.24) cannot read it, so the IDE builds no
        // symbols for ColorRGBa & friends, and every gutter/completion resolution silently fails.
        intellijIdeaCommunity("2025.2")

        // Test framework required for BasePlatformTestCase/myFixture (IntelliJ Platform Gradle Pluemgin 2.x).
        // The Java framework supplies IdeaTestUtil and DefaultLightProjectDescriptor used by the tests.
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)

//        create(providers.gradleProperty("platformType"), providers.gradleProperty("platformVersion"))
//
//        // Plugin Dependencies. Uses `platformBundledPlugins` property from the gradle.properties file for bundled IntelliJ Platform plugins.
//        bundledPlugins(providers.gradleProperty("platformBundledPlugins").map { it.split(',') })
//
//        // Plugin Dependencies. Uses `platformPlugins` property from the gradle.properties file for plugin from JetBrains Marketplace.
//        plugins(providers.gradleProperty("platformPlugins").map { it.split(',') })
//
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")
    }
}

tasks.test {
    // The IntelliJ light-fixture tests (which boot a real in-memory IDE with indexing) are memory hungry;
    // 2g avoids OOM-kills (exit 137) and GC-thrash that otherwise surface as flaky "Too long completion" errors.
    maxHeapSize = "2g"
}

//intellijPlatform {
//    pluginConfiguration {
//        name = providers.gradleProperty("pluginName")
//        version = providers.gradleProperty("pluginVersion")
//
//        // Extract the <!-- Plugin description --> section from README.md and provide for the plugin's manifest
//        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
//            val start = "<!-- Plugin description -->"
//            val end = "<!-- Plugin description end -->"
//
//            with(it.lines()) {
//                if (!containsAll(listOf(start, end))) {
//                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
//                }
//                subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
//            }
//        }
//
//        val changelog = project.changelog // local variable for configuration cache compatibility
//        // Get the latest available change notes from the changelog file
//        changeNotes = providers.gradleProperty("pluginVersion").map { pluginVersion ->
//            with(changelog) {
//                renderItem(
//                    (getOrNull(pluginVersion) ?: getUnreleased()).withHeader(false).withEmptySections(false),
//                    Changelog.OutputType.HTML,
//                )
//            }
//        }
//
//        ideaVersion {
//            sinceBuild = "242"
//            // No restrictions on compatible IDE versions
//            untilBuild = ""
//        }
//    }
//
//    signing {
//        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
//        privateKey = providers.environmentVariable("PRIVATE_KEY")
//        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
//    }
//
//    publishing {
//        token = providers.environmentVariable("PUBLISH_TOKEN")
//        // The pluginVersion is based on the SemVer (https://semver.org) and supports pre-release labels, like 2.1.7-alpha.3
//        // Specify pre-release label to publish the plugin in a custom Release Channel automatically. Read more:
//        // https://plugins.jetbrains.com/docs/intellij/deployment.html#specifying-a-release-channel
//        channels = providers.gradleProperty("pluginVersion")
//            .map { listOf(it.substringAfter('-', "").substringBefore('.').ifEmpty { "default" }) }
//    }
//}
//
//// Configure Gradle IntelliJ Plugin
//// Read more: https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html
//
//// Configure Gradle Changelog Plugin - read more: https://github.com/JetBrains/gradle-changelog-plugin
//changelog {
//    groups = listOf("Added", "Changed", "Removed", "Fixed")
//    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
//}
//
//// Configure Gradle Kover Plugin - read more: https://github.com/Kotlin/kotlinx-kover#configuration
//kover {
//    reports {
//        total {
//            xml {
//                onCheck = true
//            }
//        }
//    }
//}
//
//val defaultIntellijSourcesPath: String = File("$projectDir/../intellij-community").absolutePath
//
tasks {
//    publishPlugin {
//        dependsOn(patchChangelog)
//    }
//
//    buildSearchableOptions {
//        enabled = false
//    }
//
//    val test by getting(Test::class) {
//        systemProperties(
//            // This should always be an absolute path
//            "idea.home.path" to (System.getenv("INTELLIJ_SOURCES") ?: defaultIntellijSourcesPath),
//            "version_used_for.openrndr" to libs.versions.openrndr.get(),
//            "version_used_for.orx" to libs.versions.orx.get(),
//        )
//        // Run the test fixtures in the Kotlin K2 (Analysis API) plugin mode by default; override with
//        // -PuseK2=false to exercise K1 mode instead. The plugin code itself is mode-agnostic.
//        systemProperty("idea.kotlin.plugin.use.k2", providers.gradleProperty("useK2").orElse("true").get())
//
//        // The IntelliJ test fixtures (especially in K2 mode) are memory hungry.
//        maxHeapSize = "2g"
//    }
//
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

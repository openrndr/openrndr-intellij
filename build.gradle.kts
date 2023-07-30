import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML

// Largely based on intellij-platform-plugin-template
// https://github.com/JetBrains/intellij-platform-plugin-template/blob/main/build.gradle.kts

fun properties(key: String) = providers.gradleProperty(key)
fun environment(key: String) = providers.environmentVariable(key)

plugins {
    java
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.intellij)
    alias(libs.plugins.changelog)
    alias(libs.plugins.kover)
}

group = "org.openrndr.plugin.intellij"
version = properties("pluginVersion").get()

repositories {
    mavenCentral()
    mavenLocal()
}

dependencies {
    implementation(libs.openrndr.color) { isTransitive = false }
    implementation(libs.openrndr.math) { isTransitive = false }
    implementation(libs.orx.color) { isTransitive = false }
}

kotlin {
    jvmToolchain(17)
}

// Configure Gradle IntelliJ Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html
intellij {
    pluginName = properties("pluginName")
    // https://www.jetbrains.com/intellij-repository/releases/
    // https://www.jetbrains.com/intellij-repository/snapshots/
    version = "222.4554.10"
    type = "IC" // Target IDE Platform

    plugins = listOf("com.intellij.java", "org.jetbrains.kotlin")
}

// Configure Gradle Changelog Plugin - read more: https://github.com/JetBrains/gradle-changelog-plugin
changelog {
    groups = listOf("Added", "Changed", "Removed", "Fixed")
    repositoryUrl = "https://github.com/openrndr/openrndr-intellij"
}

// Configure Gradle Kover Plugin - read more: https://github.com/Kotlin/kotlinx-kover#configuration
koverReport {
    defaults {
        xml {
            onCheck = true
        }
    }
}

val defaultIntellijSourcesPath: String = File("$projectDir/../intellij-community").absolutePath

tasks {
    wrapper {
        gradleVersion = "8.3"
    }

    val test by getting(Test::class) {
        systemProperties(
            // This should always be an absolute path
            "idea.home.path" to (System.getenv("INTELLIJ_SOURCES") ?: defaultIntellijSourcesPath),
            "version_used_for.openrndr" to libs.versions.openrndr.get(),
            "version_used_for.orx" to libs.versions.orx.get(),
            "version_used_for.kotlin" to libs.versions.kotlin.get()
        )
    }

    buildSearchableOptions {
        enabled = false
    }

    patchPluginXml {
        version = properties("pluginVersion")
        sinceBuild = "222"
        // No restrictions on compatible IDE versions
        untilBuild = ""

        // Extract the <!-- Plugin description --> section from README.md and provide for the plugin's manifest
        pluginDescription = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"

            with(it.lines()) {
                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
            }
        }

        val changelog = project.changelog // local variable for configuration cache compatibility
        // Get the latest available change notes from the changelog file
        changeNotes = properties("pluginVersion").map { pluginVersion ->
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased()).withHeader(false).withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }
    }

    signPlugin {
        certificateChain = environment("CERTIFICATE_CHAIN")
        privateKey = environment("PRIVATE_KEY")
        password = environment("PRIVATE_KEY_PASSWORD")
    }

    publishPlugin {
        dependsOn("patchChangelog")
        token = environment("PUBLISH_TOKEN")
        // The pluginVersion is based on the SemVer (https://semver.org) and supports pre-release labels, like 2.1.7-alpha.3
        // Specify pre-release label to publish the plugin in a custom Release Channel automatically. Read more:
        // https://plugins.jetbrains.com/docs/intellij/deployment.html#specifying-a-release-channel
        channels =
            properties("pluginVersion").map { listOf(it.split('-').getOrElse(1) { "default" }.split('.').first()) }
    }
}
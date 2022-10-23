import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML

// Largely based on intellij-platform-plugin-template
// https://github.com/JetBrains/intellij-platform-plugin-template/blob/main/build.gradle.kts

@Suppress("DSL_SCOPE_VIOLATION")
plugins {
    java
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.intellij)
    alias(libs.plugins.changelog)
}

group = "org.openrndr.plugin.intellij"
version = "1.1.0"

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
    pluginName.set(findProperty("pluginName").toString())
    // https://www.jetbrains.com/intellij-repository/releases/
    // https://www.jetbrains.com/intellij-repository/snapshots/
    version.set("222.4345.14")
    type.set("IC") // Target IDE Platform

    plugins.set(listOf("com.intellij.java", "org.jetbrains.kotlin"))
}

// Configure Gradle Changelog Plugin - read more: https://github.com/JetBrains/gradle-changelog-plugin
changelog {
    groups.set(listOf("Added", "Changed", "Removed", "Fixed"))
    repositoryUrl.set("https://github.com/openrndr/openrndr-intellij")
}

val defaultIntellijSourcesPath: String = File("$projectDir/../intellij-community").absolutePath

tasks {
    wrapper {
        gradleVersion = "7.5.1"
    }

    @Suppress("UNUSED_VARIABLE")
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
        version.set(project.version.toString())
        sinceBuild.set("222")
        // No restrictions on compatible IDE versions
        untilBuild.set("")
        // Extract the <!-- Plugin description --> section from README.md and provide for the plugin's manifest
        pluginDescription.set(
            file("README.md").readText().lines().run {
                val start = "<!-- Plugin description -->"
                val end = "<!-- Plugin description end -->"

                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end))
            }.joinToString("\n").let { markdownToHTML(it) }
        )

        // Get the latest available change notes from the changelog file
        changeNotes.set(provider {
            with(changelog) {
                renderItem(
                    getOrNull(project.version.toString()) ?: getLatest(),
                    Changelog.OutputType.HTML,
                )
            }
        })
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        dependsOn("patchChangelog")
        token.set(System.getenv("PUBLISH_TOKEN"))
        // pluginVersion is based on the SemVer (https://semver.org) and supports pre-release labels, like 2.1.7-alpha.3
        // Specify pre-release label to publish the plugin in a custom Release Channel automatically. Read more:
        // https://plugins.jetbrains.com/docs/intellij/deployment.html#specifying-a-release-channel
        channels.set(listOf(version.toString().split('-').getOrElse(1) { "default" }.split('.').first()))
    }
}
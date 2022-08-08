import org.jetbrains.changelog.markdownToHTML

fun properties(key: String) = project.findProperty(key).toString()

plugins {
    java
    kotlin("jvm") version "1.7.10"
    id("org.jetbrains.intellij") version "1.8.0"
    id("org.jetbrains.changelog") version "1.3.1"
}

group = "org.openrndr.plugin.intellij"
version = properties("pluginVersion")

repositories {
    mavenCentral()
    mavenLocal()
}

// Keep in mind that test cases have their dependencies defined by the BasePlatformTestCase#getProjectDescriptor,
// so they might not have the same versions as declared here. Inconsistent versions can cause failing tests.
dependencies {
    implementation("org.openrndr:openrndr-color:0.4.1-rc.1")
    implementation("org.openrndr:openrndr-math:0.4.1-rc.1")
    implementation("org.openrndr.extra:orx-color:0.4.1-rc.1")
}

// OPENRNDR/ORX includes *a lot* of transitive dependencies that are completely unnecessary to us
configurations.implementation {
    exclude(group = "org.jetbrains.kotlin")
    exclude(group = "org.jetbrains.kotlinx")
    exclude(group = "io.github.microutils")
    exclude(group = "org.openrndr", module = "openrndr-application")
    exclude(group = "org.openrndr", module = "openrndr-draw")
    exclude(group = "org.openrndr", module = "openrndr-filter")
    exclude(group = "org.openrndr.extra", module = "orx-parameters")
    exclude(group = "org.openrndr.extra", module = "orx-shader-phrases")
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

// Configure Gradle IntelliJ Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html
intellij {
    pluginName.set(properties("pluginName"))
    version.set("222.3345.118")
    type.set("IC") // Target IDE Platform

    plugins.set(listOf("com.intellij.java", "org.jetbrains.kotlin"))
}

// Configure Gradle Changelog Plugin - read more: https://github.com/JetBrains/gradle-changelog-plugin
changelog {
    version.set(properties("pluginVersion"))
    groups.set(emptyList())
}

val defaultIntellijSourcesPath: String = File("$projectDir/../intellij-community").absolutePath

tasks {
    wrapper {
        gradleVersion = "7.5.1"
    }

    // https://youtrack.jetbrains.com/issue/IDEA-278926#focus=Comments-27-5561012.0-0
    val test by getting(Test::class) {
        isScanForTestClasses = false
        // Only run tests from classes that end with "Test"
        include("**/*Test.class")
        systemProperties(
            // This should always be an absolute path
            "idea.home.path" to (System.getenv("INTELLIJ_SOURCES") ?: defaultIntellijSourcesPath)
        )
    }

    buildSearchableOptions {
        enabled = false
    }

    patchPluginXml {
        version.set(properties("pluginVersion"))
        sinceBuild.set("222")
        untilBuild.set("223.*")

        // Extract the <!-- Plugin description --> section from README.md and provide for the plugin's manifest
        pluginDescription.set(
            projectDir.resolve("README.md").readText().lines().run {
                val start = "<!-- Plugin description -->"
                val end = "<!-- Plugin description end -->"

                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end))
            }.joinToString("\n").run(::markdownToHTML)
        )

        // Get the latest available change notes from the changelog file
        changeNotes.set(provider(changelog.run {
            getOrNull(properties("pluginVersion")) ?: getLatest()
        }::toHTML))
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
        channels.set(listOf(properties("pluginVersion").split('-').getOrElse(1) { "default" }.split('.').first()))
    }
}
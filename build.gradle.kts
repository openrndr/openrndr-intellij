fun properties(key: String) = project.findProperty(key).toString()

plugins {
    java
    kotlin("jvm") version "1.7.10"
    id("org.jetbrains.intellij") version "1.7.0"
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

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
    }
}

// Configure Gradle IntelliJ Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html
intellij {
    version.set("222.3345.118")
    type.set("IC") // Target IDE Platform

    plugins.set(listOf("com.intellij.java", "org.jetbrains.kotlin"))
}

tasks {
    wrapper {
        gradleVersion = "7.5"
    }

    // https://youtrack.jetbrains.com/issue/IDEA-278926#focus=Comments-27-5561012.0-0
    val test by getting(Test::class) {
        isScanForTestClasses = false
        // Only run tests from classes that end with "Test"
        include("**/*Test.class")
        // TODO: Consider environment variable for this
        systemProperties("idea.home.path" to File("$projectDir/../intellij-community").absolutePath)
    }

    buildSearchableOptions {
        enabled = false
    }

    patchPluginXml {
        sinceBuild.set("222")
        untilBuild.set("223.*")
        version.set(properties("pluginVersion"))
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
        // pluginVersion is based on the SemVer (https://semver.org) and supports pre-release labels, like 2.1.7-alpha.3
        // Specify pre-release label to publish the plugin in a custom Release Channel automatically. Read more:
        // https://plugins.jetbrains.com/docs/intellij/deployment.html#specifying-a-release-channel
        channels.set(listOf(properties("pluginVersion").split('-').getOrElse(1) { "default" }.split('.').first()))
    }
}

plugins {
    java
    kotlin("jvm") version "1.7.10"
    id("org.jetbrains.intellij") version "1.7.0"
}

group = "ro.vech"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    mavenLocal()
}

// Keep in mind that test cases have their dependencies defined by the BasePlatformTestCase#getProjectDescriptor,
// so they might not have the same versions as declared here. Inconsistent versions can cause failing tests.
dependencies {
    implementation("org.openrndr:openrndr-color:0.5.1-SNAPSHOT")
    implementation("org.openrndr:openrndr-math:0.5.1-SNAPSHOT")
    implementation("org.openrndr.extra:orx-color:0.5.1-SNAPSHOT")
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
    }
}

// Configure Gradle IntelliJ Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html
intellij {
    version.set("222-EAP-SNAPSHOT")
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
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }
}

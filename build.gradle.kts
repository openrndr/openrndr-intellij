import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    java
    kotlin("jvm") version "1.7.0"
    id("org.jetbrains.intellij") version "1.6.0"
}

group = "ro.vech"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    mavenLocal()
    maven(url = "https://maven.openrndr.org")
}

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
    version.set("2021.3")
    type.set("IC") // Target IDE Platform

    plugins.set(listOf("org.jetbrains.kotlin"))
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "11"
        targetCompatibility = "11"
    }
    withType<KotlinCompile> {
        kotlinOptions.jvmTarget = "11"
    }

    // https://youtrack.jetbrains.com/issue/IDEA-278926#focus=Comments-27-5561012.0-0
    val test by getting(Test::class) {
        isScanForTestClasses = false
        // Only run tests from classes that end with "Test"
        include("**/*Test.class")
    }

    buildSearchableOptions {
        enabled = false
    }

    patchPluginXml {
        sinceBuild.set("213")
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

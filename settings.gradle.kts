rootProject.name = "openrndr-intellij"

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    versionCatalogs {
        create("libs") {
            version("openrndr", "0.4.1-rc.2")
            version("orx", "0.4.1-rc.2")

            library("openrndr-color", "org.openrndr", "openrndr-color").versionRef("openrndr")
            library("openrndr-math", "org.openrndr", "openrndr-math").versionRef("openrndr")
            library("orx-color", "org.openrndr.extra", "orx-color").versionRef("orx")

            plugin("kotlin-jvm", "org.jetbrains.kotlin.jvm").version("1.7.10")
            plugin("intellij", "org.jetbrains.intellij").version("1.8.0")
            plugin("changelog", "org.jetbrains.changelog").version("1.3.1")
        }
    }
}
package org.openrndr.plugin.intellij

import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.OrderEnumerator
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.walk

/**
 * The orx modules a program imports, and which of them a project lacks.
 *
 * orx package names do not follow the module names (orx-jumpflood is `org.openrndr.extra.jumpfill`, orx-image-fit
 * is `org.openrndr.extra.imageFit`), so packages are looked up in a table made from the orx jars:
 * `./gradlew updateOrxPackages` regenerates `orx/packages.properties`.
 */
object OrxModules {

    /** An orx module: its artifact (orx-mesh) and its key in the orx version catalog (mesh-core). */
    data class OrxModule(val artifact: String, val key: String)

    /** Package to module, from `orx/packages.properties`. */
    private val packages: Map<String, OrxModule> by lazy {
        val properties = Properties()
        OrxModules::class.java.getResourceAsStream("/orx/packages.properties")?.use { properties.load(it) }
        properties.stringPropertyNames().associateWith { name ->
            val (artifact, key) = properties.getProperty(name).split(':')
            OrxModule(artifact, key)
        }
    }

    private val IMPORT = Regex("""^\s*import\s+([\w.]+)""", RegexOption.MULTILINE)

    /** The orx modules [code] imports from. */
    fun imported(code: String): Set<OrxModule> =
        IMPORT.findAll(code).mapNotNull { moduleOf(it.groupValues[1]) }.toSet()

    /** The module of an imported class, function or package (star import): the longest package in the table it starts with. */
    fun moduleOf(import: String): OrxModule? {
        var name = import.trimEnd('.')
        while (true) {
            packages[name]?.let { return it }
            name = name.substringBeforeLast('.', "").ifEmpty { return null }
        }
    }

    /**
     * The [modules] that are not among [module]'s libraries, directly or through other libraries.
     * Gradle names the libraries `Gradle: org.openrndr.extra:orx-gui:0.5.0`, and `orx-color-jvm` for the multiplatform ones.
     */
    fun missing(module: Module, modules: Collection<OrxModule>): List<OrxModule> {
        val libraries = mutableListOf<String>()
        OrderEnumerator.orderEntries(module).recursively().librariesOnly().forEachLibrary { library ->
            library.name?.let { libraries += it }
            true
        }
        return modules.filter { orx -> libraries.none { it.contains(":${orx.artifact}:") || it.contains(":${orx.artifact}-jvm:") } }
    }

    /** The template's way of naming the orx version in `gradle/libs.versions.toml`: `orx = "0.5.0"` or `orx = { require = "0.5.0" }`. */
    private val ORX_VERSION = Regex("""^orx\s*=\s*(?:\{\s*require\s*=\s*)?"([^"]+)"""", RegexOption.MULTILINE)
    private val CATALOG_ENTRY = Regex("""^([\w-]+)\s*=\s*\{[^}]*\bname\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)

    /** The orx version of the project in [root], from its `gradle/libs.versions.toml`. */
    fun projectVersion(root: Path): String? {
        val versions = root.resolve("gradle/libs.versions.toml").takeIf { it.isRegularFile() } ?: return null
        return ORX_VERSION.find(versions.readText())?.groupValues?.get(1)
    }

    /**
     * The catalog keys of orx [version], by artifact, from the catalog Gradle downloaded when it last synced the project;
     * null when it is not in the Gradle cache.
     */
    fun catalog(version: String): Map<String, String>? {
        val home = System.getenv("GRADLE_USER_HOME")?.let { Path.of(it) } ?: Path.of(System.getProperty("user.home"), ".gradle")
        val dir = home.resolve("caches/modules-2/files-2.1/org.openrndr.extra/orx-module-catalog/$version")
        if (!dir.isDirectory()) return null
        val toml = dir.walk().firstOrNull { it.name == "orx-module-catalog-$version.toml" } ?: return null
        return catalogKeys(toml.readText())
    }

    /** The `key = {group = "org.openrndr.extra", name = "orx-gui", …}` lines of a version catalog, as artifact to key. */
    fun catalogKeys(toml: String): Map<String, String> =
        CATALOG_ENTRY.findAll(toml).associate { it.groupValues[2] to it.groupValues[1] }
}

/**
 * Adding orx modules to a `build.gradle.kts` made from the current OPENRNDR template, which lists them as
 * `implementation(orx.<key>)` lines in `dependencies { }`. Other layouts are not edited.
 */
object OrxBuildFile {

    private val ORX_LINE = Regex("""^([ \t]*)implementation\(orx\.[\w.]+\)[^\n]*""", RegexOption.MULTILINE)

    /** The catalog accessor for [key]: Gradle turns dashes into dots (`kinect-v1-core` -> `orx.kinect.v1.core`). */
    fun accessor(key: String) = "orx." + key.replace('-', '.').replace('_', '.')

    /**
     * [text] with an `implementation(orx.<key>)` line for each of [keys]: a commented-out line for the key is
     * uncommented, others go after the last `implementation(orx.…)` line. Null when [text] has no such line.
     */
    fun withModules(text: String, keys: Collection<String>): String? {
        val indent = ORX_LINE.findAll(text).lastOrNull()?.groupValues?.get(1) ?: return null
        var result = text
        val added = mutableListOf<String>()
        for (key in keys) {
            val line = "implementation(${accessor(key)})"
            if (Regex("""^[ \t]*${Regex.escape(line)}""", RegexOption.MULTILINE).containsMatchIn(result)) continue
            val commented = Regex("""^[ \t]*//[ \t]*${Regex.escape(line)}[ \t]*$""", RegexOption.MULTILINE).find(result)
            if (commented != null) result = result.replaceRange(commented.range, indent + line)
            else added += line
        }
        if (added.isEmpty()) return result
        val last = ORX_LINE.findAll(result).last()
        return result.substring(0, last.range.last + 1) +
            added.joinToString("") { "\n$indent$it" } +
            result.substring(last.range.last + 1)
    }
}

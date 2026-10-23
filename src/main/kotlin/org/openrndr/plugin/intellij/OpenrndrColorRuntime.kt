package org.openrndr.plugin.intellij

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import java.awt.Color
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * A color expression described as plain data (JVM class and member names plus argument values), so it can be
 * worked out in the Kotlin analysis and then evaluated against the project's own OPENRNDR classes.
 */
sealed interface ColorCall {
    /** `new owner(args)`; a `null` argument means the parameter's default value. */
    data class Construct(val owner: String, val paramTypes: List<String>, val args: List<ColorArg?>) : ColorCall

    /**
     * A method call. [owner] is null for a top-level function or extension, whose file class is looked up by name.
     * [receiver] is the object instance for members of `object`s / companions, else null.
     */
    data class Invoke(
        val pkg: String, val owner: String?, val method: String, val receiver: ColorArg?,
        val paramTypes: List<String>, val args: List<ColorArg?>,
    ) : ColorCall
}

/** Names offered after `ColorRGBa.`: factory functions and named colors. */
data class CompanionMembers(val factories: Set<String>, val presets: Set<String>)

/**
 * Names offered after a color value (`ColorRGBa.PINK.`), by section: stored [properties] (`r`, `alpha`,
 * `linearity`), [operations] returning a color (`opacify`, `shade`), [queries] for other values (`luminance`,
 * `minValue`) and [conversions] (`toHSVa`, `toVector4`).
 */
data class ColorMembers(
    val properties: Set<String>, val operations: Set<String>, val queries: Set<String>, val conversions: Set<String>,
    /** Package of each extension member (orx), to find its declaration. */
    val extensionPackages: Map<String, String>,
)

/**
 * What `drawer.` offers that belongs in its sections: settable [styleProperties] that are also `DrawStyle`
 * properties (`fill`, `stroke`, `strokeWeight`, plus `drawStyle`), and settable [matrices] (`view`, `model`).
 */
data class DrawerMembers(val styleProperties: Set<String>, val matrices: Set<String>)

/**
 * What a vector (`Vector2`, `Vector3`, `Vector4`) offers, by section: stored [properties] (`x`, `y`), [operators]
 * (`plus`, `times`), [geometry] returning a vector of the same kind (`normalized`, `rotate`, `mix`), [queries]
 * (`length`, `dot`), [swizzles] (`xy0`, `yx`) and [conversions] (`toDoubleArray`, `vector3`).
 */
data class VectorMembers(
    val properties: Set<String>, val operators: Set<String>, val geometry: Set<String>, val queries: Set<String>,
    val swizzles: Set<String>, val conversions: Set<String>,
    /** Package of each extension member, to find its declaration. */
    val extensionPackages: Map<String, String>,
)

/** What an events object like `mouse.` offers: plain [properties] (`position`) and [events] (`buttonDown`, `dragged`). */
data class EventMembers(val properties: Set<String>, val events: Set<String>)

sealed interface ColorArg {
    data class Const(val value: Any) : ColorArg
    data class EnumEntry(val enumClass: String, val name: String) : ColorArg
    data class StaticField(val owner: String, val field: String) : ColorArg
    data class Value(val call: ColorCall) : ColorArg
}

/**
 * OPENRNDR's color classes loaded from the project's own jars (one [OpenrndrColorEvaluator] per module, rebuilt
 * when the project's dependencies change), so gutter colors always match the OPENRNDR version the sketch runs with.
 */
@Service(Service.Level.PROJECT)
class OpenrndrColorRuntime(private val project: Project) : Disposable {

    private class Loaded(val stamp: Long, val evaluator: OpenrndrColorEvaluator)

    private val loaded = ConcurrentHashMap<Module, Loaded>()

    /** Runs [call] and returns the resulting color object, or null if it cannot be evaluated. */
    fun evaluate(module: Module, call: ColorCall): Any? = withEvaluator(module) { evaluate(call) }

    /** The on-screen (sRGB) color of an OPENRNDR color object. */
    fun toAwt(module: Module, color: Any): Color? = withEvaluator(module) { toAwt(color) }

    /** The name of the `Linearity` of a `ColorRGBa`, or null for other color models. */
    fun linearityOf(module: Module, color: Any): String? = withEvaluator(module) { linearityOf(color) }

    /** See [OpenrndrColorEvaluator.colorMembers]. */
    fun colorMembers(module: Module, colorClass: String): ColorMembers? = withEvaluator(module) { colorMembers(colorClass) }

    /** See [OpenrndrColorEvaluator.drawerMembers]. */
    fun drawerMembers(module: Module): DrawerMembers? = withEvaluator(module) { drawerMembers() }

    /** See [OpenrndrColorEvaluator.eventMembers]. */
    fun eventMembers(module: Module, className: String): EventMembers? = withEvaluator(module) { eventMembers(className) }

    /** See [OpenrndrColorEvaluator.vectorMembers]. */
    fun vectorMembers(module: Module, className: String): VectorMembers? = withEvaluator(module) { vectorMembers(className) }

    /** See [OpenrndrColorEvaluator.companionMembers]. */
    fun companionMembers(module: Module, colorClass: String): CompanionMembers? =
        withEvaluator(module) { companionMembers(colorClass) }

    /** See [OpenrndrColorEvaluator.components]. */
    fun components(module: Module, color: Color, model: String, linearity: String?): List<Double>? =
        withEvaluator(module) { components(color, model, linearity) }

    private fun <T> withEvaluator(module: Module, block: OpenrndrColorEvaluator.() -> T?): T? = try {
        evaluator(module)?.block()
    } catch (e: Throwable) {
        if (e is VirtualMachineError) throw e
        LOG.debug("OPENRNDR color evaluation failed", e)
        null
    }

    private fun evaluator(module: Module): OpenrndrColorEvaluator? {
        val stamp = ProjectRootManager.getInstance(project).modificationCount
        loaded[module]?.let { if (it.stamp == stamp) return it.evaluator }
        val jars = OrderEnumerator.orderEntries(module).recursively().librariesOnly().classes().roots
            .mapNotNull { (JarFileSystem.getInstance().getVirtualFileForJar(it) ?: it).let(VfsUtilCore::virtualToIoFile) }
            .filter { it.exists() }
            .distinct()
        if (jars.none { it.name.startsWith("openrndr-color") }) return null
        val fresh = Loaded(stamp, OpenrndrColorEvaluator(jars))
        loaded.put(module, fresh)?.evaluator?.close()
        return fresh.evaluator
    }

    override fun dispose() {
        loaded.values.forEach { it.evaluator.close() }
        loaded.clear()
    }

    companion object {
        private val LOG = logger<OpenrndrColorRuntime>()

        fun getInstance(project: Project): OpenrndrColorRuntime = project.service()
    }
}

/**
 * Evaluates [ColorCall]s with OPENRNDR classes loaded from [jars] in their own class loader. Only the JDK is
 * shared with the IDE (Kotlin's stdlib comes from the jars too), and only code in OPENRNDR's color packages is called.
 */
class OpenrndrColorEvaluator(private val jars: List<File>) : AutoCloseable {

    private val loader = URLClassLoader(jars.map { it.toURI().toURL() }.toTypedArray(), ClassLoader.getPlatformClassLoader())

    private val companions = ConcurrentHashMap<String, CompanionMembers>()
    private val colorMembers = ConcurrentHashMap<String, ColorMembers>()

    /** File classes by package and function name, found by scanning the jars once; [Unit] marks a miss. */
    private val fileClasses = ConcurrentHashMap<String, Any>()

    fun evaluate(call: ColorCall): Any? = when (call) {
        is ColorCall.Construct -> {
            requireColorPackage(call.owner)
            val owner = loader.loadClass(call.owner)
            val types = call.paramTypes.map { type(it) }
            if (call.args.all { it != null }) {
                owner.getConstructor(*types.toTypedArray()).newInstance(*values(call.args, types))
            } else {
                // Kotlin's synthetic constructor for default arguments: (params…, mask, DefaultConstructorMarker)
                val marker = loader.loadClass("kotlin.jvm.internal.DefaultConstructorMarker")
                owner.getConstructor(*(types + Int::class.javaPrimitiveType!! + marker).toTypedArray())
                    .newInstance(*values(call.args, types), mask(call.args), null)
            }
        }

        is ColorCall.Invoke -> {
            requireColorPackage(call.pkg)
            val types = call.paramTypes.map { type(it) }
            val receiver = call.receiver?.let { value(it, null) }
            val owner = call.owner?.let { loader.loadClass(it) }
                ?: fileClass(call.pkg, call.method, types, defaults = call.args.any { it == null })
                ?: return null
            if (call.args.all { it != null }) {
                owner.getMethod(call.method, *types.toTypedArray()).invoke(receiver, *values(call.args, types))
            } else {
                // Kotlin's static helper for default arguments: name$default([receiver,] params…, mask, Object)
                val receiverType = if (receiver != null) listOf(owner) else emptyList()
                val method = owner.getMethod(
                    "${call.method}\$default",
                    *(receiverType + types + Int::class.javaPrimitiveType!! + Any::class.java).toTypedArray(),
                )
                val receiverValue = if (receiver != null) arrayOf(receiver) else emptyArray()
                method.invoke(null, *receiverValue, *values(call.args, types), mask(call.args), null)
            }
        }
    }

    /** The on-screen (sRGB) color of an OPENRNDR color object. */
    fun toAwt(color: Any): Color {
        val srgb = call(call(color, "toRGBa"), "toSRGB")
        fun c(name: String) = component(srgb, name).toFloat().coerceIn(0f, 1f)
        return Color(c("r"), c("g"), c("b"), c("alpha"))
    }

    /** The name of the `Linearity` of a `ColorRGBa`, or null for other color models. */
    fun linearityOf(color: Any): String? = if (color.javaClass.name != RGBA) null else call(color, "getLinearity").toString()

    /**
     * [color] as components of the given model: `rgb` in [linearity] (r, g, b, alpha), `hsv` (h, s, v, alpha)
     * or `hsl` (h, s, l, alpha).
     */
    fun components(color: Color, model: String, linearity: String?): List<Double>? {
        val (r, g, b, a) = color.getRGBComponents(null).map { it.toDouble() }
        val linearityClass = loader.loadClass(LINEARITY)
        val srgb = linearityClass.getField("SRGB").get(null)
        val double = Double::class.javaPrimitiveType!!
        val rgba = loader.loadClass(RGBA).getConstructor(double, double, double, double, linearityClass).newInstance(r, g, b, a, srgb)
        return when (model) {
            "rgb" -> {
                val target = linearityClass.getField(linearity ?: "SRGB").get(null)
                val converted = rgba.javaClass.getMethod("toLinearity", linearityClass).invoke(rgba, target)
                listOf("r", "g", "b", "alpha").map { component(converted, it) }
            }
            "hsv" -> call(rgba, "toHSVa").let { hsv -> listOf("h", "s", "v", "alpha").map { component(hsv, it) } }
            "hsl" -> call(rgba, "toHSLa").let { hsl -> listOf("h", "s", "l", "alpha").map { component(hsl, it) } }
            else -> null
        }
    }

    /**
     * What `ColorRGBa.` (or another color class's companion) offers: factory functions returning the class
     * (`fromHex`, `fromVector`, orx extensions) and named colors (`RED`, orx presets like `ORANGE_RED`).
     */
    fun companionMembers(colorClass: String): CompanionMembers = companions.getOrPut(colorClass) {
        requireColorPackage(colorClass)
        val owner = loader.loadClass(colorClass)
        val companion = loader.loadClass("$colorClass\$Companion")
        val factories = mutableSetOf<String>()
        val presets = mutableSetOf<String>()
        fun add(method: Method, parameters: Int) {
            if (method.returnType != owner || '$' in method.name) return
            if (method.name.startsWith("get") && method.parameterCount == parameters) presets += method.name.removePrefix("get")
            else factories += method.name
        }
        // Members of the companion
        companion.declaredMethods.filter { Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers) }.forEach { add(it, 0) }
        // Extensions on the companion in the color packages (orx presets and factories)
        for (cls in topLevelClasses()) {
            // A class whose signatures refer to something missing from the classpath is skipped
            val methods = runCatching { cls.declaredMethods }.getOrNull() ?: continue
            methods
                .filter { Modifier.isPublic(it.modifiers) && Modifier.isStatic(it.modifiers) && it.parameterTypes.firstOrNull() == companion }
                .forEach { add(it, 1) }
        }
        CompanionMembers(factories, presets)
    }

    /**
     * What a value of [colorClass] offers, by section: members of the class and extensions from the color
     * packages (orx). Stored properties are the class's fields; everything else is sorted by name and return type.
     */
    fun colorMembers(colorClass: String): ColorMembers = colorMembers.getOrPut(colorClass) {
        requireColorPackage(colorClass)
        val owner = loader.loadClass(colorClass)
        val properties = owner.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) && '$' !in it.name }
            .map { it.name }
            .toSet()
        val operations = mutableSetOf<String>()
        val queries = mutableSetOf<String>()
        val conversions = mutableSetOf<String>()
        val extensionPackages = mutableMapOf<String, String>()
        fun add(method: Method, receiverParameters: Int) {
            if ('$' in method.name || method.returnType == Void.TYPE) return
            val getter = method.parameterCount == receiverParameters && method.name.startsWith("get") && method.name.length > 3
            val name = if (getter) method.name.removePrefix("get").replaceFirstChar { it.lowercaseChar() } else method.name
            if (name in properties || name in IGNORED || name.matches(COMPONENT)) return
            if (receiverParameters == 1) extensionPackages.putIfAbsent(name, method.declaringClass.packageName)
            when {
                (name.startsWith("to") && name.length > 2 && name[2].isUpperCase()) || name.startsWith("convert") -> conversions += name
                producesColors(method) -> operations += name
                else -> queries += name
            }
        }
        owner.methods.filter { !Modifier.isStatic(it.modifiers) && it.declaringClass != Any::class.java }.forEach { add(it, 0) }
        for (cls in topLevelClasses()) {
            val methods = runCatching { cls.declaredMethods }.getOrNull() ?: continue
            methods
                .filter { Modifier.isPublic(it.modifiers) && Modifier.isStatic(it.modifiers) && it.parameterTypes.firstOrNull() == owner }
                .forEach { add(it, 1) }
        }
        // A name in more than one section (overloads) stays in the first one
        ColorMembers(properties, operations, queries - operations, conversions - operations - queries, extensionPackages)
    }

    private fun isColorType(type: Class<*>) = COLOR_PACKAGES.any { type.name.startsWith("$it.") }

    /** Returns a color, or colors (`List<ColorRGBa>` from orx's harmonies like `triadic`, arrays). */
    private fun producesColors(method: Method): Boolean {
        if (isColorType(method.returnType) || method.returnType.componentType?.let(::isColorType) == true) return true
        val generic = method.genericReturnType as? java.lang.reflect.ParameterizedType ?: return false
        return generic.actualTypeArguments.any { (it as? Class<*>)?.let(::isColorType) == true }
    }

    /**
     * The style properties and transform matrices of `org.openrndr.draw.Drawer`. Only reflection on the classes:
     * no OPENRNDR code is run.
     */
    fun drawerMembers(): DrawerMembers = drawer ?: run {
        val drawerClass = loader.loadClass("org.openrndr.draw.Drawer")
        val styleClass = loader.loadClass("org.openrndr.draw.DrawStyle")
        fun getters(cls: Class<*>) = cls.methods
            .filter { !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.name.startsWith("get") && it.name.length > 3 }
            .associate { it.name.removePrefix("get").replaceFirstChar(Char::lowercaseChar) to it.returnType }
        val setters = drawerClass.methods
            .filter { it.parameterCount == 1 && it.name.startsWith("set") && it.name.length > 3 }
            .map { it.name.removePrefix("set").replaceFirstChar(Char::lowercaseChar) }
            .toSet()
        val settable = getters(drawerClass).filterKeys { it in setters }
        val styleNames = getters(styleClass).keys
        DrawerMembers(
            styleProperties = settable.keys.filter { it in styleNames || it == "drawStyle" }.toSet(),
            matrices = settable.filterValues { it.name == "org.openrndr.math.Matrix44" }.keys,
        ).also { drawer = it }
    }

    @Volatile
    private var drawer: DrawerMembers? = null

    /**
     * The properties of an events object (`org.openrndr.MouseEvents`), split into `Event<…>` properties and the
     * rest. Only reflection on the class: no OPENRNDR code is run.
     */
    fun eventMembers(className: String): EventMembers = events.getOrPut(className) {
        val cls = loader.loadClass(className)
        val getters = cls.methods
            .filter { !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.name.startsWith("get") && it.name.length > 3 }
            .associate { it.name.removePrefix("get").replaceFirstChar(Char::lowercaseChar) to it.returnType.name }
            .filterKeys { it != "class" }
        EventMembers(
            properties = getters.filterValues { it != EVENT }.keys,
            events = getters.filterValues { it == EVENT }.keys,
        )
    }

    private val events = ConcurrentHashMap<String, EventMembers>()

    /**
     * The members of a vector class and its extensions in `org.openrndr.math` / `org.openrndr.shape`, by section.
     * Only reflection on the classes: no OPENRNDR code is run.
     */
    fun vectorMembers(className: String): VectorMembers = vectors.getOrPut(className) {
        val owner = loader.loadClass(className)
        val properties = owner.declaredFields.filter { !Modifier.isStatic(it.modifiers) && '$' !in it.name }.map { it.name }.toSet()
        val operators = mutableSetOf<String>()
        val geometry = mutableSetOf<String>()
        val queries = mutableSetOf<String>()
        val swizzles = mutableSetOf<String>()
        val conversions = mutableSetOf<String>()
        val extensionPackages = mutableMapOf<String, String>()
        fun add(method: Method, receiverParameters: Int) {
            if ('$' in method.name || method.returnType == Void.TYPE) return
            val getter = method.parameterCount == receiverParameters && method.name.startsWith("get") && method.name.length > 3
            val name = if (getter) method.name.removePrefix("get").replaceFirstChar { it.lowercaseChar() } else method.name
            if (name in properties || name in IGNORED || name.matches(COMPONENT) || name == "zero") return
            if (receiverParameters == 1) extensionPackages.putIfAbsent(name, method.declaringClass.packageName)
            when {
                name in VECTOR_OPERATORS -> operators += name
                SWIZZLE.matches(name) -> swizzles += name
                (name.startsWith("to") && name.length > 2 && name[2].isUpperCase()) || name in VECTOR_CONVERSIONS -> conversions += name
                method.returnType == owner -> geometry += name
                else -> queries += name
            }
        }
        owner.methods.filter { !Modifier.isStatic(it.modifiers) && it.declaringClass != Any::class.java }.forEach { add(it, 0) }
        for (cls in topLevelClasses(VECTOR_PACKAGES)) {
            val methods = runCatching { cls.declaredMethods }.getOrNull() ?: continue
            methods
                .filter { Modifier.isPublic(it.modifiers) && Modifier.isStatic(it.modifiers) && it.parameterTypes.firstOrNull() == owner }
                .forEach { add(it, 1) }
        }
        // A name in more than one section (overloads) stays in the first one
        VectorMembers(
            properties, operators, geometry - operators, queries - geometry - operators, swizzles,
            conversions - geometry - queries, extensionPackages,
        )
    }

    private val vectors = ConcurrentHashMap<String, VectorMembers>()

    /** Top-level classes of the given packages (OPENRNDR's color packages by default), from the jars. */
    private fun topLevelClasses(packages: List<String> = COLOR_PACKAGES): List<Class<*>> = jars.filter { it.isFile && it.name.endsWith(".jar") }.flatMap { jar ->
        ZipFile(jar).use { zip ->
            zip.entries().asSequence().map { it.name }
                .filter { name -> name.endsWith(".class") && '$' !in name && packages.any { name.startsWith(it.replace('.', '/') + "/") } }
                .mapNotNull { name -> runCatching { loader.loadClass(name.removeSuffix(".class").replace('/', '.')) }.getOrNull() }
                .toList()
        }
    }

    override fun close() = loader.close()

    /** The file class (`ColorRGBaKt`, `ColorsKt`, …) in [pkg] that declares the top-level function [method]. */
    private fun fileClass(pkg: String, method: String, types: List<Class<*>>, defaults: Boolean): Class<*>? {
        val key = "$pkg.$method(${types.joinToString { it.name }})"
        fileClasses[key]?.let { return it as? Class<*> }
        val prefix = pkg.replace('.', '/') + "/"
        for (jar in jars.filter { it.isFile && it.name.endsWith(".jar") }) {
            ZipFile(jar).use { zip ->
                for (entry in zip.entries()) {
                    val name = entry.name
                    if (!name.startsWith(prefix) || !name.endsWith(".class") || '$' in name) continue
                    if ('/' in name.substring(prefix.length)) continue
                    val cls = runCatching { loader.loadClass(name.removeSuffix(".class").replace('/', '.')) }.getOrNull() ?: continue
                    val found = cls.methods.any {
                        it.name == method && Modifier.isStatic(it.modifiers) && it.parameterTypes.toList() == types
                    }
                    // A function whose parameters all have defaults may only exist as name$default plus the full form
                    if (found || (defaults && cls.methods.any { it.name == "$method\$default" })) {
                        fileClasses[key] = cls
                        return cls
                    }
                }
            }
        }
        fileClasses[key] = Unit
        return null
    }

    private fun values(args: List<ColorArg?>, types: List<Class<*>>): Array<Any?> =
        args.mapIndexed { i, arg -> if (arg == null) placeholder(types[i]) else value(arg, types[i]) }.toTypedArray()

    private fun value(arg: ColorArg, type: Class<*>?): Any? = when (arg) {
        is ColorArg.Const -> coerce(arg.value, type)
        is ColorArg.EnumEntry -> loader.loadClass(arg.enumClass).getField(arg.name).get(null)
        is ColorArg.StaticField -> loader.loadClass(arg.owner).getField(arg.field).get(null)
        is ColorArg.Value -> evaluate(arg.call)
    }

    private fun type(name: String): Class<*> = PRIMITIVES[name] ?: loader.loadClass(name)

    private fun requireColorPackage(name: String) {
        require(COLOR_PACKAGES.any { name == it || name.startsWith("$it.") }) { "Not an OPENRNDR color class: $name" }
    }

    companion object {
        const val RGBA = "org.openrndr.color.ColorRGBa"
        private const val LINEARITY = "org.openrndr.color.Linearity"

        /** Packages whose code may be evaluated. */
        val COLOR_PACKAGES = listOf("org.openrndr.color", "org.openrndr.extra.color")

        private const val EVENT = "org.openrndr.events.Event"

        /** Where vector extensions live: mix, clamp, smoothstep (math) and map(rect, rect), clamp(rect) (shape). */
        private val VECTOR_PACKAGES = listOf("org.openrndr.math", "org.openrndr.shape")
        private val VECTOR_OPERATORS = setOf("plus", "minus", "times", "div", "rem", "unaryMinus")
        private val VECTOR_CONVERSIONS = setOf("vector3", "vector4")
        /** Swizzles like xy, yx, xy0, xyz1. */
        private val SWIZZLE = Regex("^[xyzw][xyzw01]{1,3}$")

        /** Members that are not about the color: they stay outside the sections. */
        private val IGNORED = setOf("toString", "equals", "hashCode", "copy", "class", "javaClass")
        private val COMPONENT = Regex("component\\d+")

        private val PRIMITIVES = mapOf(
            "double" to Double::class.javaPrimitiveType!!, "float" to Float::class.javaPrimitiveType!!,
            "int" to Int::class.javaPrimitiveType!!, "long" to Long::class.javaPrimitiveType!!,
            "boolean" to Boolean::class.javaPrimitiveType!!,
        )

        private fun mask(args: List<ColorArg?>): Int =
            args.foldIndexed(0) { i, mask, arg -> if (arg == null) mask or (1 shl i) else mask }

        private fun placeholder(type: Class<*>): Any? = when (type) {
            Double::class.javaPrimitiveType -> 0.0
            Float::class.javaPrimitiveType -> 0f
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Boolean::class.javaPrimitiveType -> false
            else -> null
        }

        private fun coerce(value: Any, type: Class<*>?): Any = when {
            value !is Number -> value
            type == Double::class.javaPrimitiveType || type == Double::class.javaObjectType -> value.toDouble()
            type == Float::class.javaPrimitiveType || type == Float::class.javaObjectType -> value.toFloat()
            type == Int::class.javaPrimitiveType || type == Int::class.javaObjectType -> value.toInt()
            type == Long::class.javaPrimitiveType || type == Long::class.javaObjectType -> value.toLong()
            else -> value
        }

        private fun call(target: Any, method: String): Any = target.javaClass.getMethod(method).invoke(target)

        /** A color component, whether exposed as `getR()` (ColorRGBa) or `h()` (ColorHSVa). */
        private fun component(target: Any, name: String): Double {
            val getter = "get" + name.replaceFirstChar { it.uppercaseChar() }
            val method: Method = target.javaClass.methods.firstOrNull { it.parameterCount == 0 && (it.name == getter || it.name == name) }
                ?: error("No component $name on ${target.javaClass.name}")
            return (method.invoke(target) as Number).toDouble()
        }
    }
}

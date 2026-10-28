package org.openrndr.plugin.intellij

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.codeInsight.lookup.LookupElementRenderer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Completion after `ColorRGBa.` with the real OPENRNDR 0.5.0 and orx jars (from the local Gradle cache; the test
 * is skipped when they are not there): factory functions first, then named colors, then everything else.
 */
class OpenrndrColorCompletionTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = object : DefaultLightProjectDescriptor() {
        override fun configureModule(module: Module, model: ModifiableRootModel, contentEntry: ContentEntry) {
            super.configureModule(module, model, contentEntry)
            for ((name, jar) in JARS) {
                val library = PsiTestUtil.addLibrary(model, name, jar.parent, jar.name)
                // With the sources jar when it is there, for the KDoc summaries
                val sources = jar.parentFile.parentFile.walkTopDown().firstOrNull { it.name == jar.name.removeSuffix(".jar") + "-sources.jar" }
                    ?.let { JarFileSystem.getInstance().refreshAndFindFileByPath(it.path + "!/") }
                if (sources != null) library.modifiableModel.apply { addRoot(sources, OrderRootType.SOURCES) }.commit()
            }
        }
    }.withRepositoryLibrary("org.jetbrains.kotlin:kotlin-stdlib:2.3.0")

    fun `test sections after ColorRGBa dot`() {
        if (JARS.size < 4) return println("OPENRNDR 0.5.0 jars not in the Gradle cache; skipped")
        myFixture.configureByText(
            "Sketch.kt",
            """
            import org.openrndr.color.ColorRGBa

            fun main() {
                val c = ColorRGBa.<caret>
            }
            """.trimIndent()
        )
        myFixture.completeBasic()
        val lookup = myFixture.lookup ?: error("no completion list")
        val all = lookup.items
        // Section headings are separator items
        val separators = all.filter { it.javaClass.simpleName.contains("Separator") }
        println("separators: " + separators.map { it.lookupString + " / " + it.javaClass.simpleName })
        val items = all.filterNot { it in separators }.map { it.lookupString }
        println("first 8: " + items.take(8))

        // Constructors and factories first (each overload is its own item: fromHex(Int), fromHex(String), …)
        val first = items.takeWhile { it in setOf("ColorRGBa", "fromHex", "fromVector") }
        assertEquals("constructors and factories first", setOf("ColorRGBa", "fromHex", "fromVector"), first.toSet())
        assertEquals("the constructor leads its section", "ColorRGBa", first.first())
        // Then the named colors of OPENRNDR and orx together
        val presets = items.drop(first.size).takeWhile { Regex("^[A-Z][A-Z0-9_]*$").matches(it) }
        assertTrue("OPENRNDR presets right after the factories", presets.containsAll(listOf("RED", "PINK", "WHITE")))
        assertTrue("orx presets in the same section", "ORANGE_RED" in presets)
        // Then the rest
        assertTrue("something else after the presets", items.size > first.size + presets.size)
        println("presets: ${presets.size}, then: " + items.drop(first.size + presets.size).take(5))
        assertEquals("a heading before each section, then the closing line", listOf("Constructors and factories", "Color presets", " "), separators.map { it.lookupString })
        assertTrue("first row is the first heading", all.first() == separators.first())
        assertEquals("second heading right before the presets", all.indexOf(separators[1]) + 1, all.indexOfFirst { it.lookupString == presets.first() })
        assertFalse("a heading is not selected", lookup.currentItem in separators)

        // The orx presets do not repeat where they come from
        val orangeRed = all.first { it.lookupString == "ORANGE_RED" }
        val presentation = LookupElementPresentation.renderElement(orangeRed)
        assertTrue("no tail text on presets: ${presentation.tailText}", presentation.tailText.isNullOrEmpty())

        // Picking the constructor turns `ColorRGBa.` into `ColorRGBa(…)`
        lookup.currentItem = all.first { it.lookupString == "ColorRGBa" }
        myFixture.finishLookup('\n')
        myFixture.checkResult(
            """
            import org.openrndr.color.ColorRGBa

            fun main() {
                val c = ColorRGBa(<caret>)
            }
            """.trimIndent()
        )
    }

    fun `test sections after a color value`() {
        if (JARS.size < 4) return println("OPENRNDR 0.5.0 jars not in the Gradle cache; skipped")
        myFixture.configureByText(
            "Sketch.kt",
            """
            import org.openrndr.color.ColorRGBa

            fun main() {
                val c = ColorRGBa.PINK.<caret>
            }
            """.trimIndent()
        )
        myFixture.completeBasic()
        val all = myFixture.lookup?.items ?: error("no completion list")
        val rows = all.map { if (it.javaClass.simpleName.contains("Separator")) (if (it.lookupString.isBlank()) "----" else "## ${it.lookupString}") else it.lookupString }
        println("value rows: " + rows.take(40))

        val headings = rows.filter { it.startsWith("## ") }
        assertEquals(listOf("## Properties", "## Linear Operations", "## Operations", "## Query", "## Convert"), headings)
        fun section(title: String) = rows.drop(rows.indexOf("## $title") + 1).takeWhile { !it.startsWith("## ") }.toSet()
        assertEquals("first row is a heading", "## Properties", rows.first())
        assertTrue(section("Properties").containsAll(listOf("r", "g", "b", "alpha", "linearity")))
        assertTrue(section("Linear Operations").containsAll(listOf("plus", "minus", "times", "div")))
        assertTrue(section("Operations").containsAll(listOf("opacify", "shade", "shiftHue")))
        assertTrue("operators only in Linear Operations", "plus" !in section("Operations"))
        assertTrue(section("Query").containsAll(listOf("luminance", "minValue", "maxValue")))
        assertTrue(section("Convert").containsAll(listOf("toHSVa", "toHSLa", "toSRGB")))
        assertTrue("other members after the sections", rows.indexOf("equals") > rows.indexOf("## Convert"))

        // Members show the first sentence of their KDoc; extensions lose Kotlin's "for ColorRGBa in …"
        // Rendered in the background with a read action, like the completion list does
        fun tail(name: String): String {
            val item = all.first { it.lookupString == name }
            return ApplicationManager.getApplication().executeOnPooledThread<String> {
                runReadActionBlocking {
                    val presentation = LookupElementPresentation.renderElement(item)
                    @Suppress("UNCHECKED_CAST")
                    (item.expensiveRenderer as LookupElementRenderer<LookupElement>?)?.renderElement(item, presentation)
                    presentation.tailText.orEmpty()
                }
            }.get(30, TimeUnit.SECONDS)
        }
        println("opacify tail: " + tail("opacify"))
        println("toOKLABa tail: " + tail("toOKLABa"))
        assertFalse("no receiver note", tail("toOKLABa").contains(" for ColorRGBa in "))
        if (SOURCES) assertTrue("KDoc summary on opacify: ${tail("opacify")}", tail("opacify").contains("opacity", ignoreCase = true))
        // An orx extension with KDoc (matchLinearity in orx-color's tools) is found through its package
        println("matchLinearity tail: " + tail("matchLinearity"))
        if (SOURCES) assertTrue("KDoc summary on an extension: ${tail("matchLinearity")}", tail("matchLinearity").trim().length > "(other: ColorRGBa)".length)
    }

    fun `test linear operations are shown and written as operators`() {
        if (JARS.size < 4) return println("OPENRNDR 0.5.0 jars not in the Gradle cache; skipped")
        myFixture.configureByText(
            "Sketch.kt",
            """
            import org.openrndr.color.ColorRGBa

            fun main() {
                val c = ColorRGBa.PINK.<caret>
            }
            """.trimIndent()
        )
        myFixture.completeBasic()
        val lookup = myFixture.lookup ?: error("no completion list")
        val texts = lookup.items.filter { it.lookupString in setOf("plus", "times", "div", "minus") }
            .map { LookupElementPresentation.renderElement(it).itemText }
        println("operator rows: $texts")
        assertTrue(texts.toString(), "ColorRGBa + ColorRGBa" in texts)
        assertTrue(texts.toString(), "ColorRGBa * Double" in texts)

        lookup.currentItem = lookup.items.first { it.lookupString == "plus" && LookupElementPresentation.renderElement(it).itemText == "ColorRGBa + ColorRGBa" }
        myFixture.finishLookup('\n')
        myFixture.checkResult(
            """
            import org.openrndr.color.ColorRGBa

            fun main() {
                val c = ColorRGBa.PINK + <caret>
            }
            """.trimIndent()
        )
    }

    fun `test drawer sections`() {
        if (JARS.none { it.first.contains("openrndr-draw") }) return println("openrndr-draw 0.5.0 not in the Gradle cache; skipped")
        myFixture.configureByText(
            "Sketch.kt",
            """
            import org.openrndr.draw.Drawer

            fun draw(drawer: Drawer) {
                drawer.<caret>
            }
            """.trimIndent()
        )
        myFixture.completeBasic()
        val all = myFixture.lookup?.items ?: error("no completion list")
        val rows = all.map { if (it.javaClass.simpleName.contains("Separator")) (if (it.lookupString.isBlank()) "----" else "## ${it.lookupString}") else it.lookupString }
        println("drawer rows: " + rows.take(60))

        assertEquals(
            listOf("## Drawing properties", "## Transforms", "## Text", "## Shapes", "## Images", "## Advanced"),
            rows.filter { it.startsWith("## ") },
        )
        fun section(title: String) = rows.drop(rows.indexOf("## $title") + 1).takeWhile { !it.startsWith("## ") }
        assertEquals("## Drawing properties", rows.first())
        assertEquals(
            listOf("fill", "stroke", "strokeWeight", "lineCap", "lineJoin", "drawStyle", "width", "height", "bounds"),
            section("Drawing properties").take(9),
        )
        assertEquals(listOf("view", "projection", "model", "ortho", "perspective", "translate", "rotate", "scale"), section("Transforms").distinct().take(8))
        assertEquals(listOf("text", "texts"), section("Text").distinct().take(2))
        assertEquals(listOf("circle", "rectangle", "lineSegment", "contour", "shape"), section("Shapes").distinct().take(5))
        assertEquals(listOf("image"), section("Images").distinct().take(1))
        // The last section runs into the unsectioned rest, so only its start is checked
        assertEquals(listOf("vertexBuffer", "vertexBufferInstances"), section("Advanced").distinct().take(2))
        // No `Unit` return type on the drawing functions; properties keep their types
        fun type(name: String) = LookupElementPresentation.renderElement(all.first { it.lookupString == name }).typeText
        assertTrue("no Unit on circle: ${type("circle")}", type("circle").isNullOrEmpty())
        assertFalse("fill keeps its type", type("fill").isNullOrEmpty())
        assertTrue("other members after the sections", rows.indexOf("clear") > rows.indexOf("## Advanced"))
        // A closing line between the last section and the rest
        val closing = rows.indexOf("----")
        assertTrue("closing line after the last section", closing > rows.indexOf("## Advanced") && closing < rows.indexOf("clear"))
        assertEquals("one closing line", 1, rows.count { it == "----" })
    }

    fun `test mouse sections`() {
        if (JARS.none { it.first.contains("openrndr-application") }) return println("openrndr-application 0.5.0 not in the Gradle cache; skipped")
        myFixture.configureByText(
            "Sketch.kt",
            """
            import org.openrndr.MouseEvents

            fun listen(mouse: MouseEvents) {
                mouse.<caret>
            }
            """.trimIndent()
        )
        myFixture.completeBasic()
        val all = myFixture.lookup?.items ?: error("no completion list")
        val rows = all.map { if (it.javaClass.simpleName.contains("Separator")) (if (it.lookupString.isBlank()) "----" else "## ${it.lookupString}") else it.lookupString }
        println("mouse rows: " + rows.take(15))
        assertEquals(
            listOf("## Properties", "position", "## Events", "buttonUp", "buttonDown", "dragged", "entered", "exited", "moved", "scrolled", "----"),
            rows.take(11),
        )
    }

    private fun rowsAt(code: String): List<String> {
        myFixture.configureByText("Sketch.kt", code.trimIndent())
        myFixture.completeBasic()
        val all = myFixture.lookup?.items ?: error("no completion list")
        return all.map { if (it.javaClass.simpleName.contains("Separator")) (if (it.lookupString.isBlank()) "----" else "## ${it.lookupString}") else it.lookupString }
    }

    fun `test keyboard has an events section only`() {
        if (JARS.none { it.first.contains("openrndr-application") }) return println("openrndr-application 0.5.0 not in the Gradle cache; skipped")
        val rows = rowsAt(
            """
            import org.openrndr.KeyEvents

            fun listen(keyboard: KeyEvents) {
                keyboard.<caret>
            }
            """
        )
        println("keyboard rows: " + rows.take(8))
        assertEquals(listOf("## Events", "keyDown", "keyUp", "keyRepeat", "character"), rows.take(5))
        assertEquals("no empty Properties heading", listOf("## Events"), rows.filter { it.startsWith("## ") })
    }

    fun `test any type with event properties gets an events section`() {
        if (JARS.none { it.first.contains("openrndr-event") }) return println("openrndr-event 0.5.0 not in the Gradle cache; skipped")
        val rows = rowsAt(
            """
            import org.openrndr.events.Event

            open class Control {
                val focused: Event<Unit> get() = TODO()
            }

            class Slider : Control() {
                val label = "speed"
                val valueChanged: Event<Double> get() = TODO()
                val released: Event<Unit> get() = TODO()
            }

            fun listen(slider: Slider) {
                slider.<caret>
            }
            """
        )
        println("slider rows: " + rows.take(8))
        assertEquals("## Events", rows.first())
        assertEquals(setOf("valueChanged", "released", "focused"), rows.drop(1).takeWhile { !it.startsWith("## ") }.take(3).toSet())
        assertTrue("other members after the events", rows.indexOf("label") > 3)
    }

    fun `test program sections after program dot`() {
        if (JARS.none { it.first.contains("openrndr-application") }) return println("openrndr-application 0.5.0 not in the Gradle cache; skipped")
        val rows = rowsAt(
            """
            import org.openrndr.Program

            fun sketch(program: Program) {
                program.<caret>
            }
            """
        )
        println("program rows: " + rows.take(30))
        val headings = rows.filter { it.startsWith("## ") }
        assertEquals("## Events", rows.first())
        // Only sections with items get a heading, in Program's section order
        val order = listOf("## Events", "## Drawing", "## Images", "## Text", "## Time", "## Input", "## Coroutines")
        assertEquals(order.filter { it in headings }, headings)
        assertTrue(headings.containsAll(listOf("## Events", "## Drawing", "## Time", "## Coroutines")))
        fun section(title: String) = rows.drop(rows.indexOf("## $title") + 1).takeWhile { !it.startsWith("## ") }
        assertTrue(section("Events").containsAll(listOf("ended", "produceAssets", "requestAssets")))
        assertEquals(listOf("drawer", "backgroundColor", "width", "height"), section("Drawing").distinct())
        assertTrue(section("Time").containsAll(listOf("clock", "frameCount")))
        assertEquals("launch", section("Coroutines").first())
    }

    fun `test program sections inside a program block`() {
        if (JARS.none { it.first.contains("openrndr-application") }) return println("openrndr-application 0.5.0 not in the Gradle cache; skipped")
        val rows = rowsAt(
            """
            import org.openrndr.Program

            fun program(init: Program.() -> Unit) {}

            fun main() {
                program {
                    <caret>
                }
            }
            """
        )
        println("program block rows: " + rows.take(20))
        assertEquals("## Events", rows.first())
        assertTrue(rows.indexOf("## Drawing") in 1 until rows.indexOf("drawer"))
    }

    fun `test program sections inside an extend block`() {
        if (JARS.none { it.first.contains("openrndr-application") }) return println("openrndr-application 0.5.0 not in the Gradle cache; skipped")
        val stub = """
            import org.openrndr.Program

            fun program(init: Program.() -> Unit) {}
            fun Program.extend(userDraw: Program.() -> Unit) {}
        """
        val empty = rowsAt(stub + """
            fun main() {
                program {
                    extend {
                        <caret>
                    }
                }
            }
        """)
        println("extend rows, nothing typed: " + empty.take(12))
        myFixture.lookup?.hideLookup(true)
        val typed = rowsAt(stub + """
            fun main() {
                program {
                    extend {
                        d<caret>
                    }
                }
            }
        """)
        println("extend rows, 'd' typed: " + typed.take(12))
        assertEquals("## Events", empty.first())
        // While typing, headings stay for sections with matching items, in section order
        assertEquals(listOf("## Drawing", "drawer"), typed.take(2))
        assertEquals(listOf("## Drawing", "## Coroutines"), typed.filter { it.startsWith("## ") })
        assertTrue("dispatcher under Coroutines", typed.indexOf("dispatcher") == typed.indexOf("## Coroutines") + 1)
    }

    fun `test transform builder section has only its own members`() {
        if (JARS.none { it.first.contains("openrndr-application") }) return println("openrndr-application 0.5.0 not in the Gradle cache; skipped")
        fun rowsFor(typed: String): Pair<List<String>, List<com.intellij.codeInsight.lookup.LookupElement>> {
            myFixture.configureByText(
                "Sketch.kt",
                """
                import org.openrndr.Program
                import org.openrndr.math.transforms.transform

                fun program(init: Program.() -> Unit) {}

                // Named like TransformBuilder members, but they are not
                fun translate(x: Double): Int = 0
                fun Program.rotate(turns: Int) {}
                fun scaleAll(factor: Double) {}

                fun main() {
                    program {
                        val m = transform {
                            $typed<caret>
                        }
                    }
                }
                """.trimIndent()
            )
            myFixture.completeBasic()
            val all = myFixture.lookup?.items ?: emptyList()
            val rows = all.map {
                if (it.javaClass.simpleName.contains("Separator")) (if (it.lookupString.isBlank()) "----" else "## ${it.lookupString}")
                else it.lookupString + LookupElementPresentation.renderElement(it).tailText.orEmpty()
            }
            myFixture.lookup?.hideLookup(true)
            return rows to all
        }

        val (rows, _) = rowsFor("")
        println("transform rows: " + rows.take(16))
        assertEquals("## Transform", rows.first())
        val section = rows.drop(1).takeWhile { !it.startsWith("## ") && it.substringBefore('(') in setOf("translate", "rotate", "scale", "multiply", "transform") }
        assertEquals(listOf("translate", "rotate", "scale", "multiply", "transform"), section.map { it.substringBefore('(') }.distinct())
        assertFalse("not the Program sections here", rows.contains("## Drawing"))

        // No `Unit` return type on the builder's functions; the transform property keeps Matrix44
        rowsFor("")
        myFixture.completeBasic()
        val items = myFixture.lookup?.items.orEmpty()
        // Only the builder's own members: the look-alike `Program.rotate` keeps its Unit
        val types = items.filter { it.lookupString in setOf("translate", "rotate", "scale", "transform") && !it.javaClass.simpleName.contains("Separator") }
            .filterNot { LookupElementPresentation.renderElement(it).tailText.orEmpty().contains(" for ") }
            .take(11).associate { it.lookupString + LookupElementPresentation.renderElement(it).tailText.orEmpty() to LookupElementPresentation.renderElement(it).typeText }
        println("transform types: $types")
        assertTrue("no Unit: $types", types.filterKeys { !it.startsWith("transform") }.values.all { it.isNullOrEmpty() })
        assertEquals("Matrix44", types["transform"])
        myFixture.lookup?.hideLookup(true)

        // Look-alikes that are not TransformBuilder members stay out of the section
        for (typed in listOf("tra", "rot", "sca")) {
            val (typedRows, _) = rowsFor(typed)
            println("transform rows '$typed': $typedRows")
            val inSection = typedRows.drop(1).takeWhile { !it.startsWith("## ") && it.substringBefore('(') in setOf("translate", "rotate", "scale", "multiply", "transform") }
            assertFalse("$typed: top-level translate in section", "translate(x: Double)" in inSection)
            assertFalse("$typed: Program.rotate in section", "rotate(turns: Int)" in inSection)
            assertFalse("$typed: scaleAll in section", inSection.any { it.startsWith("scaleAll") })
        }
    }

    fun `test render target builder sections`() {
        if (JARS.none { it.first.contains("openrndr-draw") }) return println("openrndr-draw 0.5.0 not in the Gradle cache; skipped")
        myFixture.configureByText(
            "Sketch.kt",
            """
            import org.openrndr.draw.renderTarget

            fun main() {
                val rt = renderTarget(640, 480) {
                    <caret>
                }
            }
            """.trimIndent()
        )
        myFixture.completeBasic()
        val all = myFixture.lookup?.items ?: error("no completion list")
        val rows = all.map {
            if (it.javaClass.simpleName.contains("Separator")) (if (it.lookupString.isBlank()) "----" else "## ${it.lookupString}")
            else it.lookupString + LookupElementPresentation.renderElement(it).tailText.orEmpty()
        }
        println("render target rows: " + rows.take(20))
        assertEquals(listOf("## Attach New", "## Attach Existing"), rows.filter { it.startsWith("## ") })
        assertEquals("## Attach New", rows.first())
        val new = sectionOf(rows, "Attach New")
        val existing = rows.drop(rows.indexOf("## Attach Existing") + 1).takeWhile { !it.startsWith("## ") && it != "----" }
        assertEquals(setOf("colorBuffer", "depthBuffer"), new.map { it.substringBefore('(') }.toSet())
        assertTrue("new: $new", new.any { it.startsWith("colorBuffer(format") } && new.any { it.startsWith("colorBuffer(name: String, format") })
        assertTrue("new: $new", new.none { "colorBuffer: ColorBuffer" in it || "depthBuffer: DepthBuffer" in it || "width" in it })
        assertEquals(setOf("colorBuffer", "depthBuffer", "arrayTexture", "cubemap", "arrayCubemap", "volumeTexture"), existing.map { it.substringBefore('(') }.toSet())
        assertTrue("existing: $existing", existing.all { Regex("""\b(ColorBuffer|DepthBuffer|ArrayTexture|ArrayCubemap|Cubemap|VolumeTexture)\b""").containsMatchIn(it) })
        // No `Unit` return type on the attachments; the deprecated `colorBuffer(width, height, …): Nothing` stays out of the sections
        val types = all.filter { it.lookupString == "colorBuffer" }.map { LookupElementPresentation.renderElement(it).typeText }
        assertEquals("no Unit: $types", listOf("Nothing"), types.filterNot { it.isNullOrEmpty() })
        assertTrue("deprecated overload after the sections", rows.indexOfFirst { it.startsWith("colorBuffer(width") }.let { it < 0 || it > rows.indexOf("----") })
    }

    fun `test render target buffer and texture sections`() {
        if (JARS.none { it.first.contains("openrndr-draw") }) return println("openrndr-draw 0.5.0 not in the Gradle cache; skipped")
        fun rowsAfter(type: String): List<String> {
            val rows = rowsAt(
                """
                import org.openrndr.draw.*

                fun use(value: $type) {
                    value.<caret>
                }
                """
            )
            myFixture.lookup?.hideLookup(true)
            println("$type rows: " + rows.take(60))
            return rows
        }
        fun headings(rows: List<String>) = rows.filter { it.startsWith("## ") }.map { it.removePrefix("## ") }

        val target = rowsAfter("RenderTarget")
        assertEquals(listOf("Properties", "Attachments", "Attach", "Clear", "Blend Modes", "Bind", "Lifecycle"), headings(target).take(7))
        assertEquals(listOf("width", "height"), sectionOf(target, "Properties").take(2))
        assertTrue(sectionOf(target, "Attachments").containsAll(listOf("colorBuffer", "depthBuffer", "colorAttachments")))
        assertTrue(sectionOf(target, "Attach").containsAll(listOf("attach", "detachColorBuffers")))
        assertEquals(listOf("clearColor", "clearDepth"), sectionOf(target, "Clear"))
        // Kotlin does not offer AutoCloseable's close here; its `use` sits with destroy
        assertEquals(listOf("destroy", "use"), sectionOf(target, "Lifecycle"))
        assertTrue("Any's members after the sections", target.indexOf("hashCode") > target.indexOf("----"))

        val color = rowsAfter("ColorBuffer")
        assertEquals(listOf("Properties", "Sampling", "Write", "Read", "Copy", "Save", "Lifecycle"), headings(color).take(7))
        assertTrue(sectionOf(color, "Sampling").containsAll(listOf("filterMin", "wrapU", "generateMipmaps")))
        assertEquals(listOf("fill", "write"), sectionOf(color, "Write"))
        assertTrue(sectionOf(color, "Read").containsAll(listOf("shadow", "read")))
        assertTrue(sectionOf(color, "Save").containsAll(listOf("saveToFile", "toDataUrl")))
        // Extensions in other files of OPENRNDR count as the class's own
        assertTrue("imageBinding in Assorted: ${sectionOf(color, "Assorted")}", "imageBinding" in sectionOf(color, "Assorted"))
        assertTrue("Copy: ${sectionOf(color, "Copy")}", sectionOf(color, "Copy").containsAll(listOf("copyTo", "createEquivalent")))

        assertEquals(listOf("Properties", "Copy", "Lifecycle"), headings(rowsAfter("DepthBuffer")).take(3))
        assertEquals(listOf("Properties", "Write", "Read", "Lifecycle"), headings(rowsAfter("VertexBuffer")).take(4))
        assertEquals(listOf("Properties", "Write", "Read", "Lifecycle"), headings(rowsAfter("IndexBuffer")).take(4))
        for (texture in listOf("ArrayTexture", "Cubemap", "ArrayCubemap", "VolumeTexture")) {
            val rows = rowsAfter(texture)
            assertEquals(texture, listOf("Properties", "Sampling", "Write", "Read", "Copy", "Lifecycle"), headings(rows).take(6))
            assertTrue(texture, "generateMipmaps" in sectionOf(rows, "Sampling"))
        }
    }

    fun `test shader shade style and vertex format sections`() {
        if (JARS.none { it.first.contains("openrndr-draw") }) return println("openrndr-draw 0.5.0 not in the Gradle cache; skipped")
        fun rows(label: String, code: String): List<String> {
            val rows = rowsAt("import org.openrndr.draw.*\n\n" + code.trimIndent())
            myFixture.lookup?.hideLookup(true)
            println("$label rows: " + rows.take(60))
            return rows
        }
        fun headings(rows: List<String>) = rows.filter { it.startsWith("## ") }.map { it.removePrefix("## ") }

        val shader = rows("Shader", "fun use(shader: Shader) {\n    shader.<caret>\n}")
        assertEquals(listOf("Bind", "Uniforms", "Bindings", "Blocks", "Lifecycle"), headings(shader).take(5))
        assertEquals(listOf("begin", "end"), sectionOf(shader, "Bind"))
        // From ShaderUniforms / ShaderBufferBindings, Shader's interfaces
        assertEquals(listOf("uniform", "hasUniform"), sectionOf(shader, "Uniforms"))
        assertTrue(sectionOf(shader, "Bindings").containsAll(listOf("buffer", "image")))

        val style = rows("ShadeStyle", "fun use(style: ShadeStyle) {\n    style.<caret>\n}")
        assertEquals(listOf("Transforms", "Preambles", "Parameters", "Buffers", "Images", "Outputs", "Combine"), headings(style).take(7))
        assertEquals(listOf("fragmentTransform", "vertexTransform", "geometryTransform"), sectionOf(style, "Transforms"))
        assertTrue("parameter from StyleParameters", "parameter" in sectionOf(style, "Parameters"))
        assertFalse("no Any members in Assorted", style.contains("## Assorted") && "equals" in sectionOf(style, "Assorted"))

        // Inside `shadeStyle { }` in a program: ShadeStyle's sections, not the program's
        val block = rows(
            "shadeStyle block",
            """
            fun program(init: org.openrndr.Program.() -> Unit) {}

            fun main() {
                program {
                    val style = shadeStyle {
                        <caret>
                    }
                }
            }
            """
        )
        assertEquals("## Transforms", block.first())
        assertFalse("not the program's sections", block.contains("## Drawing"))

        val format = rows("vertexFormat block", "fun main() {\n    val format = vertexFormat {\n        <caret>\n    }\n}")
        assertEquals(listOf("Attributes", "Padding", "Properties"), headings(format).take(3))
        assertEquals(listOf("position", "normal", "color", "textureCoordinate", "attribute"), sectionOf(format, "Attributes"))
        assertTrue("toString after the sections", format.indexOf("toString") > format.indexOf("----"))
    }

    fun `test compute style sections`() {
        if (JARS.none { it.first.contains("openrndr-draw") }) return println("openrndr-draw 0.5.0 not in the Gradle cache; skipped")
        fun headings(rows: List<String>) = rows.filter { it.startsWith("## ") }.map { it.removePrefix("## ") }
        val value = rowsAt("import org.openrndr.draw.*\n\nfun use(style: ComputeStyle) {\n    style.<caret>\n}")
        myFixture.lookup?.hideLookup(true)
        println("ComputeStyle rows: " + value.take(60))
        assertEquals(listOf("Compute", "Parameters", "Buffers", "Images", "Execute"), headings(value).take(5))
        assertEquals(listOf("computeTransform", "computePreamble", "workGroupSize"), sectionOf(value, "Compute"))
        assertTrue("parameter from StyleParameters", "parameter" in sectionOf(value, "Parameters"))
        assertTrue("buffer from StyleBufferBindings", "buffer" in sectionOf(value, "Buffers"))
        // An extension in OPENRNDR
        assertEquals(listOf("execute"), sectionOf(value, "Execute"))

        // Inside `computeStyle { }`
        val block = rowsAt("import org.openrndr.draw.*\n\nfun main() {\n    val style = computeStyle {\n        <caret>\n    }\n}")
        println("computeStyle block rows: " + block.take(20))
        assertEquals("## Compute", block.first())
    }

    fun `test compose and layer sections`() {
        if (JARS.none { it.first.contains("orx-compositor") }) return println("orx-compositor 0.5.0 not in the Gradle cache; skipped")
        fun headings(rows: List<String>) = rows.filter { it.startsWith("## ") }.map { it.removePrefix("## ") }
        fun rows(label: String, body: String): List<String> {
            val rows = rowsAt(
                """
                import org.openrndr.Program
                import org.openrndr.extra.compositor.*

                fun program(init: Program.() -> Unit) {}

                fun main() {
                    program {
                        val c = compose {
                            $body
                        }
                    }
                }
                """
            )
            myFixture.lookup?.hideLookup(true)
            println("$label rows: " + rows.take(40))
            return rows
        }

        val composite = rows("compose", "<caret>")
        // Layer's @BooleanParameter properties (enabled, invertMask) first
        assertEquals(listOf("Parameters", "Draw", "Layers", "Filters", "Properties", "Lifecycle"), headings(composite).take(6))
        assertEquals(listOf("enabled", "invertMask"), sectionOf(composite, "Parameters"))
        assertTrue(sectionOf(composite, "Draw").containsAll(listOf("draw", "mask")))
        assertEquals(listOf("layer", "aside"), sectionOf(composite, "Layers"))
        assertTrue(sectionOf(composite, "Filters").containsAll(listOf("post", "blend")))
        assertTrue(sectionOf(composite, "Properties").containsAll(listOf("clearColor", "colorType")))

        val layer = rows("layer", "layer {\n    <caret>\n}")
        assertEquals(listOf("Parameters", "Draw", "Layers", "Filters", "Properties"), headings(layer).take(5))
        assertFalse("no Lifecycle on a Layer", "Lifecycle" in headings(layer))

        // In draw { } (no receiver of its own) the program's sections, not the layer's
        val draw = rows("draw", "layer {\n    draw {\n        <caret>\n    }\n}")
        assertFalse("not the layer's sections in draw { }: ${headings(draw)}", "Layers" in headings(draw))

        // After a Layer value
        val value = rowsAt("import org.openrndr.extra.compositor.*\n\nfun use(l: Layer) {\n    l.<caret>\n}")
        myFixture.lookup?.hideLookup(true)
        assertEquals(listOf("Parameters", "Draw", "Layers", "Filters", "Properties"), headings(value).take(5))
    }

    fun `test filters in post`() {
        if (JARS.none { it.first.contains("orx-fx") } || JARS.none { it.first.contains("orx-compositor") }) return println("orx-fx 0.5.0 not in the Gradle cache; skipped")
        myFixture.configureByText(
            "Sketch.kt",
            """
            import org.openrndr.draw.Filter1to1
            import org.openrndr.extra.compositor.*

            /** My own filter. */
            class MyFilter : Filter1to1(null)

            fun main() {
                val c = compose {
                    layer {
                        post(<caret>)
                    }
                }
            }
            """.trimIndent()
        )
        myFixture.completeBasic()
        val all = myFixture.lookup?.items ?: error("no completion list")
        val rows = all.map { if (it.javaClass.simpleName.contains("Separator")) (if (it.lookupString.isBlank()) "----" else "## ${it.lookupString}") else it.lookupString }
        println("post rows: " + rows.take(80))
        val headings = rows.filter { it.startsWith("## ") }.map { it.removePrefix("## ") }
        assertEquals("## Antialias", rows.first())
        assertTrue(headings.toString(), headings.containsAll(listOf("Blur", "Color", "Distort", "Edges", "Tonemap")))
        assertEquals("Assorted last", "Assorted", headings.last())
        assertEquals("sections in package order", headings.dropLast(1).sorted(), headings.dropLast(1))
        assertTrue(sectionOf(rows, "Blur").containsAll(listOf("BoxBlur", "GaussianBlur", "ApproximateGaussianBlur")))
        assertTrue("Contour in Edges", "Contour" in sectionOf(rows, "Edges"))
        // Filter2to1s of orx-fx and the project's own filters go to Assorted; the base classes not at all
        val assorted = sectionOf(rows, "Assorted")
        assertTrue("Filter2to1 Add in Assorted: $assorted", "Add" in assorted)
        assertTrue("own filter in Assorted: $assorted", "MyFilter" in assorted)
        assertFalse("no base classes: $assorted", assorted.any { it in setOf("Filter", "Filter1to1", "Filter2to1", "Filter1to2", "Filter4to1") })
        assertEquals("BoxBlur once", 1, rows.count { it == "BoxBlur" })

        // Picking one writes it and imports it
        val boxBlur = all.first { it.lookupString == "BoxBlur" }
        myFixture.lookup!!.currentItem = boxBlur
        myFixture.finishLookup(com.intellij.codeInsight.lookup.Lookup.NORMAL_SELECT_CHAR)
        val text = myFixture.editor.document.text
        println("after BoxBlur: $text")
        assertTrue(text, text.contains("post(BoxBlur())"))
        assertTrue(text, text.contains("import org.openrndr.extra.fx.blur.BoxBlur"))
    }

    fun `test filter2to1s in blend`() {
        if (JARS.none { it.first.contains("orx-fx") } || JARS.none { it.first.contains("orx-compositor") }) return println("orx-fx 0.5.0 not in the Gradle cache; skipped")
        val rows = rowsAt(
            """
            import org.openrndr.draw.Filter2to1
            import org.openrndr.extra.compositor.*

            class MyBlend : Filter2to1(null)

            fun main() {
                val c = compose {
                    layer {
                        blend(<caret>)
                    }
                }
            }
            """
        )
        println("blend rows: " + rows.take(60))
        assertEquals(listOf("## Blend Modes", "## Filter2to1"), rows.filter { it.startsWith("## ") })
        assertEquals("## Blend Modes", rows.first())
        val modes = sectionOf(rows, "Blend Modes")
        assertTrue(modes.toString(), modes.containsAll(listOf("Add", "Multiply", "Screen", "Overlay", "Darken")))
        assertFalse("Passthrough is a Filter1to1", "Passthrough" in modes)
        val others = sectionOf(rows, "Filter2to1")
        assertTrue(others.toString(), others.containsAll(listOf("DirectionalBlur", "DisplaceBlend", "MyBlend")))
        assertFalse("no Filter1to1s: $others", others.any { it in setOf("BoxBlur", "Contour") })
    }

    fun `test filter sections with its parameters`() {
        if (JARS.none { it.first.contains("orx-fx") }) return println("orx-fx 0.5.0 not in the Gradle cache; skipped")
        fun headings(rows: List<String>) = rows.filter { it.startsWith("## ") }.map { it.removePrefix("## ") }
        val value = rowsAt("import org.openrndr.extra.fx.blur.BoxBlur\n\nfun main() {\n    val blur = BoxBlur()\n    blur.<caret>\n}")
        myFixture.lookup?.hideLookup(true)
        println("BoxBlur rows: " + value.take(30))
        assertEquals(listOf("Parameters", "Apply", "Properties", "Lifecycle"), headings(value).take(4))
        assertTrue(sectionOf(value, "Parameters").toString(), sectionOf(value, "Parameters").containsAll(listOf("spread", "gain", "window")))
        assertEquals(listOf("apply"), sectionOf(value, "Apply"))
        assertTrue(sectionOf(value, "Properties").containsAll(listOf("parameters", "padding")))
        assertTrue(sectionOf(value, "Lifecycle").containsAll(listOf("destroy", "close")))

        // In the filter's block in post(…): the filter's sections, not the layer's
        if (JARS.none { it.first.contains("orx-compositor") }) return
        val block = rowsAt(
            """
            import org.openrndr.extra.compositor.*
            import org.openrndr.extra.fx.blur.BoxBlur

            fun main() {
                val c = compose {
                    layer {
                        post(BoxBlur()) {
                            <caret>
                        }
                    }
                }
            }
            """
        )
        myFixture.lookup?.hideLookup(true)
        println("post block rows: " + block.take(20))
        assertEquals("## Parameters", block.first())
        assertFalse("not the layer's sections", "Layers" in headings(block))
    }

    fun `test parameters section for any class`() {
        if (JARS.none { it.first.contains("orx-parameters") }) return println("orx-parameters 0.5.0 not in the Gradle cache; skipped")
        val rows = rowsAt(
            """
            import org.openrndr.extra.parameters.*

            @Description("settings")
            class Settings {
                @DoubleParameter("radius", 0.0, 10.0, order = 1)
                var radius = 1.0

                @BooleanParameter("enabled", order = 0)
                var enabled = true

                @ActionParameter("reset")
                fun reset() {}

                var other = 2
            }

            fun main() {
                val settings = Settings()
                settings.<caret>
            }
            """
        )
        println("settings rows: " + rows.take(12))
        assertEquals("## Parameters", rows.first())
        // By the annotations' order, actions without one last
        assertEquals(listOf("enabled", "radius", "reset"), sectionOf(rows, "Parameters"))
        assertTrue("unannotated after the section", rows.indexOf("other") > rows.indexOf("----"))
    }

    fun `test mutable list sections`() {
        if (JARS.none { it.first.contains("openrndr-color") }) return println("OPENRNDR jars not in the Gradle cache; skipped")
        val rows = rowsAt(
            """
            fun main() {
                val xs = mutableListOf(3, 1, 2)
                xs.<caret>
            }
            """
        )
        println("list rows: " + rows.take(40))
        val headings = rows.filter { it.startsWith("## ") }
        assertEquals(
            listOf("## Read", "## Write", "## Remove", "## Sort", "## Query", "## Search", "## Filter", "## Map", "## Random", "## Assorted"),
            headings,
        )
        fun section(title: String) = rows.drop(rows.indexOf("## $title") + 1).takeWhile { !it.startsWith("## ") && it != "----" }.distinct()
        assertEquals(listOf("get", "first", "last"), section("Read").take(3))
        assertEquals(listOf("add", "addAll", "set"), section("Write").take(3))
        assertEquals(listOf("remove", "removeAt", "removeAll"), section("Remove").take(3))
        assertEquals(listOf("sort", "sorted", "sortBy"), section("Sort").take(3))
        assertEquals(listOf("size", "isEmpty", "isNotEmpty"), section("Query").take(3))
        assertEquals(listOf("contains", "containsAll", "indexOf", "lastIndexOf"), section("Search").take(4))
        assertFalse("search functions only in Search", "indexOf" in section("Query"))
        // Every section holds only its own kind: no Read function under Write, and so on
        assertTrue("Write holds writes only: ${section("Write")}", section("Write").all { it in setOf("add", "addAll", "set", "addFirst", "addLast", "fill", "plusAssign") })
        // No "for MutableCollection<in T> in kotlin.collections" notes
        val notes = myFixture.lookup!!.items.map { LookupElementPresentation.renderElement(it).tailText.orEmpty() }.filter { Regex("""\bfor\s.+\sin\s""").containsMatchIn(it) }
        assertTrue("receiver notes left: ${notes.take(5)}", notes.none { it.contains("kotlin.collections") })
        assertEquals(listOf("filter", "filterNot"), section("Filter").take(2))
        assertEquals(listOf("map", "mapIndexed"), section("Map").take(2))
        assertEquals(listOf("random", "randomOrNull", "shuffled", "shuffle"), section("Random").take(4))
        assertTrue("other collection functions in Assorted", section("Assorted").isNotEmpty())
        // Scope functions are not about the list: after the closing line
        val closing = rows.indexOf("----")
        assertTrue("let after the sections", rows.indexOf("let") > closing && closing > 0)
    }

    fun `test read only list has no write or remove sections`() {
        if (JARS.none { it.first.contains("openrndr-color") }) return println("OPENRNDR jars not in the Gradle cache; skipped")
        val rows = rowsAt(
            """
            fun main() {
                val xs = listOf(3, 1, 2)
                xs.<caret>
            }
            """
        )
        val headings = rows.filter { it.startsWith("## ") }
        println("read-only list headings: $headings")
        assertFalse(headings.contains("## Write"))
        assertFalse(headings.contains("## Remove"))
        assertEquals("## Read", headings.first())
    }

    private fun sectionOf(rows: List<String>, title: String) =
        rows.drop(rows.indexOf("## $title") + 1).takeWhile { !it.startsWith("## ") && it != "----" }.distinct()

    fun `test mutable set sections`() {
        if (JARS.none { it.first.contains("openrndr-color") }) return println("OPENRNDR jars not in the Gradle cache; skipped")
        val rows = rowsAt(
            """
            fun main() {
                val xs = mutableSetOf(3, 1, 2)
                xs.<caret>
            }
            """
        )
        val headings = rows.filter { it.startsWith("## ") }
        println("set headings: $headings")
        assertEquals(
            listOf("## Read", "## Write", "## Remove", "## Sort", "## Query", "## Search", "## Filter", "## Map", "## Random", "## Assorted"),
            headings,
        )
        assertEquals(listOf("first", "last"), sectionOf(rows, "Read").take(2))
        assertEquals(listOf("add", "addAll"), sectionOf(rows, "Write").take(2))
        assertEquals("remove", sectionOf(rows, "Remove").first())
        assertTrue(sectionOf(rows, "Filter").containsAll(listOf("filter", "intersect", "subtract", "union")))
        assertEquals("contains", sectionOf(rows, "Search").first())
    }

    fun `test mutable map sections`() {
        if (JARS.none { it.first.contains("openrndr-color") }) return println("OPENRNDR jars not in the Gradle cache; skipped")
        val rows = rowsAt(
            """
            fun main() {
                val m = mutableMapOf("a" to 1)
                m.<caret>
            }
            """
        )
        val headings = rows.filter { it.startsWith("## ") }
        println("map headings: $headings")
        assertEquals(listOf("## Read", "## Write", "## Remove"), headings.take(3))
        assertFalse("maps have no Random section", "## Random" in headings)
        assertEquals("## Assorted", headings.last())
        assertEquals(listOf("get", "getValue", "getOrDefault"), sectionOf(rows, "Read").take(3))
        assertEquals(listOf("put", "putAll"), sectionOf(rows, "Write").take(2))
        assertEquals(listOf("containsKey", "containsValue"), sectionOf(rows, "Search").take(2))
        assertTrue(sectionOf(rows, "Filter").containsAll(listOf("filter", "filterKeys", "filterValues")))
        assertTrue(sectionOf(rows, "Map").containsAll(listOf("map", "mapKeys", "mapValues")))
    }

    fun `test string sections`() {
        if (JARS.none { it.first.contains("openrndr-color") }) return println("OPENRNDR jars not in the Gradle cache; skipped")
        val rows = rowsAt(
            """
            fun main() {
                val name = "openrndr"
                name.<caret>
            }
            """
        )
        val headings = rows.filter { it.startsWith("## ") }
        println("string headings: $headings")
        assertEquals(
            listOf("## Read", "## Sort", "## Query", "## Search", "## Filter", "## Map", "## Convert", "## Random", "## Assorted"),
            headings,
        )
        assertEquals(listOf("get", "first", "last"), sectionOf(rows, "Read").take(3))
        assertEquals(listOf("length", "isEmpty", "isNotEmpty", "isBlank"), sectionOf(rows, "Query").take(4))
        assertEquals(listOf("contains", "startsWith", "endsWith"), sectionOf(rows, "Search").take(3))
        assertEquals(listOf("trim", "trimStart", "trimEnd"), sectionOf(rows, "Filter").take(3))
        assertEquals(listOf("uppercase", "lowercase", "replace"), sectionOf(rows, "Map").take(3))
        assertEquals(listOf("toInt", "toIntOrNull", "toDouble", "toDoubleOrNull"), sectionOf(rows, "Convert").take(4))
        assertFalse("conversions only in Convert", "toInt" in sectionOf(rows, "Map"))
        assertTrue("let after the sections", rows.indexOf("let") > rows.indexOf("----"))
    }

    fun `test overloads show their own kdoc summary`() {
        if (JARS.size < 4) return println("OPENRNDR 0.5.0 jars not in the Gradle cache; skipped")
        myFixture.configureByText(
            "Sketch.kt",
            """
            /** Appends one number. */
            fun MutableList<Int>.addNumber(n: Int) {}

            /** Parses a text and appends its number. */
            fun MutableList<Int>.addNumber(text: String) {}

            fun main() {
                val numbers = mutableListOf(1)
                numbers.addNum<caret>
            }
            """.trimIndent()
        )
        myFixture.completeBasic()
        val items = myFixture.lookup?.items.orEmpty().filter { it.lookupString == "addNumber" }
        assertEquals(2, items.size)
        val tails = items.map { item ->
            ApplicationManager.getApplication().executeOnPooledThread<String> {
                runReadActionBlocking {
                    val presentation = LookupElementPresentation.renderElement(item)
                    @Suppress("UNCHECKED_CAST")
                    (item.expensiveRenderer as LookupElementRenderer<LookupElement>?)?.renderElement(item, presentation)
                    presentation.tailText.orEmpty()
                }
            }.get(30, TimeUnit.SECONDS)
        }
        println("addNumber tails: $tails")
        assertTrue("no receiver note: $tails", tails.none { " for " in it })
        assertTrue(tails.toString(), tails.any { it.startsWith("(n: Int)") && it.contains("Appends one number.") })
        assertTrue(tails.toString(), tails.any { it.startsWith("(text: String)") && it.contains("Parses a text and appends its number.") })
    }

    fun `test vector sections`() {
        if (JARS.none { it.first.contains("openrndr-math") }) return println("OPENRNDR jars not in the Gradle cache; skipped")
        val rows = rowsAt(
            """
            import org.openrndr.math.Vector2

            fun main() {
                val v = Vector2(1.0, 2.0)
                v.<caret>
            }
            """
        )
        val headings = rows.filter { it.startsWith("## ") }
        println("vector rows: " + rows.take(30))
        assertEquals(listOf("## Properties", "## Linear Operations", "## Geometry", "## Query", "## Swizzle", "## Convert"), headings)
        assertEquals(listOf("x", "y"), sectionOf(rows, "Properties"))
        assertEquals(listOf("normalized", "perpendicular", "rotate", "mix"), sectionOf(rows, "Geometry").take(4))
        assertEquals(listOf("length", "squaredLength", "dot", "distanceTo"), sectionOf(rows, "Query").take(4))
        assertTrue(sectionOf(rows, "Swizzle").containsAll(listOf("xy0", "yx")))
        // Operators read as operators
        val operators = myFixture.lookup!!.items.filter { it.lookupString in setOf("plus", "times") }
            .map { LookupElementPresentation.renderElement(it).itemText }
        assertTrue(operators.toString(), "Vector2 + Vector2" in operators)
    }

    fun `test spatial section for a list of vectors`() {
        if (JARS.none { it.first.contains("openrndr-shape") }) return println("OPENRNDR jars not in the Gradle cache; skipped")
        myFixture.configureByText(
            "Sketch.kt",
            """
            import org.openrndr.math.Vector2

            fun main() {
                val points = listOf(Vector2(0.0, 0.0), Vector2(1.0, 1.0))
                points.<caret>
            }
            """.trimIndent()
        )
        myFixture.completeBasic()
        val all = myFixture.lookup?.items ?: error("no completion list")
        val rows = all.map {
            if (it.javaClass.simpleName.contains("Separator")) (if (it.lookupString.isBlank()) "----" else "## ${it.lookupString}")
            else it.lookupString + LookupElementPresentation.renderElement(it).tailText.orEmpty()
        }
        println("spatial rows: " + rows.take(12))
        assertEquals("## Spatial", rows.first())
        assertTrue(rows[1], rows[1].startsWith("bounds"))
        val spatial = rows.drop(1).takeWhile { !it.startsWith("## ") }
        assertTrue("average in Spatial: $spatial", spatial.any { it.startsWith("average") })
        // OPENRNDR's map(rect, rect) on points is Spatial; the standard map { } stays in Map
        assertTrue("map(rect, rect) in Spatial: $spatial", spatial.any { it.startsWith("map(sourceRectangle") })
        val mapSection = rows.drop(rows.indexOf("## Map") + 1).takeWhile { !it.startsWith("## ") }
        assertTrue("standard map in Map: $mapSection", mapSection.any { it.startsWith("map") && !it.startsWith("map(sourceRectangle") })
        assertEquals("## Read", rows.filter { it.startsWith("## ") }[1])
    }

    private companion object {
        val GRADLE = File(System.getProperty("user.home"), ".gradle/caches/modules-2/files-2.1")

        fun jar(group: String, artifact: String, version: String): File? =
            File(GRADLE, "$group/$artifact/$version").walkTopDown()
                .firstOrNull { it.name == "$artifact-$version.jar" }

        /** Whether the sources jar of openrndr-color is in the Gradle cache (for the KDoc checks). */
        val SOURCES = File(GRADLE, "org.openrndr/openrndr-color-jvm/0.5.0").walkTopDown().any { it.name == "openrndr-color-jvm-0.5.0-sources.jar" }

        /** Library name (OPENRNDR projects are recognised by "org.openrndr" in it) to jar. */
        val JARS: List<Pair<String, File>> = listOfNotNull(
            jar("org.openrndr", "openrndr-color-jvm", "0.5.0")?.let { "Gradle: org.openrndr:openrndr-color-jvm:0.5.0" to it },
            jar("org.openrndr", "openrndr-math-jvm", "0.5.0")?.let { "Gradle: org.openrndr:openrndr-math-jvm:0.5.0" to it },
            jar("org.openrndr.extra", "orx-color-jvm", "0.5.0")?.let { "Gradle: org.openrndr.extra:orx-color-jvm:0.5.0" to it },
            jar("org.jetbrains.kotlinx", "kotlinx-serialization-core-jvm", "1.11.0")?.let { "Gradle: kotlinx-serialization-core" to it },
            // For Drawer
            jar("org.openrndr", "openrndr-draw-jvm", "0.5.0")?.let { "Gradle: org.openrndr:openrndr-draw-jvm:0.5.0" to it },
            jar("org.openrndr", "openrndr-shape-jvm", "0.5.0")?.let { "Gradle: org.openrndr:openrndr-shape-jvm:0.5.0" to it },
            jar("org.openrndr", "openrndr-utils-jvm", "0.5.0")?.let { "Gradle: org.openrndr:openrndr-utils-jvm:0.5.0" to it },
            jar("org.openrndr", "openrndr-event-jvm", "0.5.0")?.let { "Gradle: org.openrndr:openrndr-event-jvm:0.5.0" to it },
            jar("org.openrndr", "openrndr-platform-jvm", "0.5.0")?.let { "Gradle: org.openrndr:openrndr-platform-jvm:0.5.0" to it },
            jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.11.0")?.let { "Gradle: kotlinx-coroutines-core" to it },
            jar("io.github.oshai", "kotlin-logging-jvm", "8.0.4")?.let { "Gradle: kotlin-logging" to it },
            // For MouseEvents
            jar("org.openrndr", "openrndr-application-jvm", "0.5.0")?.let { "Gradle: org.openrndr:openrndr-application-jvm:0.5.0" to it },
            // For compose { } and the filters in post(…)
            jar("org.openrndr.extra", "orx-compositor-jvm", "0.5.0")?.let { "Gradle: org.openrndr.extra:orx-compositor-jvm:0.5.0" to it },
            jar("org.openrndr.extra", "orx-fx-jvm", "0.5.0")?.let { "Gradle: org.openrndr.extra:orx-fx-jvm:0.5.0" to it },
            jar("org.openrndr.extra", "orx-parameters-jvm", "0.5.0")?.let { "Gradle: org.openrndr.extra:orx-parameters-jvm:0.5.0" to it },
            jar("org.openrndr.extra", "orx-shader-phrases-jvm", "0.5.0")?.let { "Gradle: org.openrndr.extra:orx-shader-phrases-jvm:0.5.0" to it },
        )
    }
}

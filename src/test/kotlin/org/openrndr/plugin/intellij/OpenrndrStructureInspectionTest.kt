package org.openrndr.plugin.intellij

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** The OPENRNDR structure warnings and their quick fixes, on plain Kotlin code (no OPENRNDR library needed). */
class OpenrndrStructureInspectionTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(OpenrndrStructureInspection())
    }

    private fun fix(before: String, fixName: String, after: String) {
        myFixture.configureByText("Sketch.kt", before)
        myFixture.launchAction(myFixture.findSingleIntention(fixName))
        myFixture.checkResult(after)
    }

    private fun warnings(text: String): List<String> {
        myFixture.configureByText("Sketch.kt", text)
        return myFixture.doHighlighting().filter { it.description?.contains("{ }") == true || it.description?.contains("graphics context") == true }
            .map { text.substring(it.startOffset, it.endOffset) }
    }

    fun `test all four mistakes are reported`() {
        val found = warnings(
            """
            fun main() = application {
                val image = loadImage("data/a.png")
                program {
                    configure { width = 720 }
                    extend {
                        val font = loadFont("data/f.otf", 24.0)
                        extend { }
                    }
                }
            }
            """.trimIndent()
        )
        assertEquals(listOf("loadImage", "configure", "loadFont", "extend"), found)
    }

    fun `test correct structure is not reported`() {
        val found = warnings(
            """
            fun main() = application {
                configure { width = 720 }
                program {
                    val image = loadImage("data/a.png")
                    extend { drawer.image(image) }
                }
            }
            """.trimIndent()
        )
        assertEquals(emptyList<String>(), found)
    }

    fun `test nested extend moves before the outer extend`() = fix(
        """
        fun main() = application {
            program {
                extend {
                    drawer.clear()
                    <caret>extend { drawer.circle() }
                }
            }
        }
        """.trimIndent(),
        "Move out of the outer extend { }",
        """
        fun main() = application {
            program {
                extend { drawer.circle() }
                extend {
                    drawer.clear()
                }
            }
        }
        """.trimIndent(),
    )

    fun `test configure moves to application before program`() = fix(
        """
        fun main() = application {
            program {
                <caret>configure { width = 720 }
                extend { }
            }
        }
        """.trimIndent(),
        "Move to application { }",
        """
        fun main() = application {
            configure { width = 720 }
            program {
                extend { }
            }
        }
        """.trimIndent(),
    )

    fun `test loading outside program moves into program and keeps order`() {
        fix(
            """
            fun main() = application {
                val image = <caret>loadImage("data/a.png")
                val font = loadFont("data/f.otf", 24.0)
                program {
                    extend { }
                }
            }
            """.trimIndent(),
            "Move into program { }",
            """
            fun main() = application {
                val font = loadFont("data/f.otf", 24.0)
                program {
                    val image = loadImage("data/a.png")
                    extend { }
                }
            }
            """.trimIndent(),
        )
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("loadFont"))
        myFixture.launchAction(myFixture.findSingleIntention("Move into program { }"))
        myFixture.checkResult(
            """
            fun main() = application {
                program {
                    val image = loadImage("data/a.png")
                    val font = loadFont("data/f.otf", 24.0)
                    extend { }
                }
            }
            """.trimIndent()
        )
    }

    fun `test loading in extend moves before extend`() = fix(
        """
        fun main() = application {
            program {
                extend {
                    val font = <caret>loadFont("data/f.otf", 24.0)
                    drawer.fontMap = font
                }
            }
        }
        """.trimIndent(),
        "Load once before extend { }",
        """
        fun main() = application {
            program {
                val font = loadFont("data/f.otf", 24.0)
                extend {
                    drawer.fontMap = font
                }
            }
        }
        """.trimIndent(),
    )

    fun `test loading that depends on the frame gets no fix`() {
        myFixture.configureByText(
            "Sketch.kt",
            """
            fun main() = application {
                program {
                    extend {
                        val name = "data/${'$'}{frameCount}.png"
                        val image = <caret>loadImage(name)
                    }
                }
            }
            """.trimIndent()
        )
        assertEmpty(myFixture.filterAvailableIntentions("Load once before extend { }"))
    }

    fun `test loading inside configure gets no fix`() {
        myFixture.configureByText(
            "Sketch.kt",
            """
            fun main() = application {
                configure { val image = <caret>loadImage("data/a.png") }
                program { }
            }
            """.trimIndent()
        )
        assertEmpty(myFixture.filterAvailableIntentions("Move into program { }"))
    }
}

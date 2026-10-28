package org.openrndr.plugin.intellij

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.vcsUtil.VcsUtil

/** Naming and writing the copies of guide programs. */
class GuideProgramsTest : LightJavaCodeInsightFixtureTestCase() {

    // A JDK and the Kotlin standard library, for finding the main function
    override fun getProjectDescriptor(): LightProjectDescriptor =
        DefaultLightProjectDescriptor().withRepositoryLibrary("org.jetbrains.kotlin:kotlin-stdlib:2.3.0")

    fun `test file names from the page title and the block position`() {
        assertEquals("DrawingCirclesRectanglesAndLines01", GuidePrograms.baseName("Drawing circles, rectangles and lines | OPENRNDR GUIDE", 1))
        assertEquals("OrxFx12", GuidePrograms.baseName("orx-fx | OPENRNDR GUIDE", 12))
        assertEquals("ImagesAndBuffers03", GuidePrograms.baseName("Images and buffers", 3))
        assertEquals("CafeCreme01", GuidePrograms.baseName("Café crème | OPENRNDR GUIDE", 1))
        assertEquals("LowLevelDrawing01", GuidePrograms.baseName("Low-level drawing | OPENRNDR GUIDE", 1))
        assertEquals("WhatIsGradle01", GuidePrograms.baseName("What is Gradle? | OPENRNDR GUIDE", 1))
        // A class name cannot start with a digit
        assertEquals("Guide3DGraphics02", GuidePrograms.baseName("3D graphics | OPENRNDR GUIDE", 2))
        assertEquals("Guide01", GuidePrograms.baseName("", 1))
    }

    fun `test letters after the number`() {
        val suffixes = GuidePrograms.suffixes().take(703).toList()
        assertEquals(listOf("a", "b", "c"), suffixes.take(3))
        assertEquals("z", suffixes[25])
        assertEquals("aa", suffixes[26])
        assertEquals("ab", suffixes[27])
        assertEquals("zz", suffixes[701])
        assertEquals("aaa", suffixes[702])
    }

    fun `test program text is put in the guide package`() {
        assertEquals("package guide\n\nimport org.openrndr.application\n\nfun main() {}\n",
            GuidePrograms.programText("\nimport org.openrndr.application\r\n\r\nfun main() {}\n\n"))
        assertEquals("package guide\n\nfun main() {}\n", GuidePrograms.programText("package examples.drawing\n\nfun main() {}"))
    }

    fun `test an unedited copy is reused and an edited one is kept`() {
        val dir = myFixture.tempDirFixture.findOrCreateDir("guide")
        val text = GuidePrograms.programText("fun main() {\n    println(1)\n}")

        val first = create(dir, text)
        assertEquals("Shapes01a.kt", first.name)
        assertEquals(text, FileDocumentManager.getInstance().getDocument(first)!!.text)
        assertEquals("same code: the same file", first, create(dir, text))

        // Only whitespace at line ends: still the guide's code
        edit(first, text.replace("println(1)", "println(1)   ") + "\n\n")
        assertEquals(first, create(dir, text))

        // An edit, saved or not: a new copy next to it
        edit(first, text.replace("println(1)", "println(2)"))
        val second = create(dir, text)
        assertEquals("Shapes01b.kt", second.name)
        assertEquals(text, FileDocumentManager.getInstance().getDocument(second)!!.text)
        assertTrue(FileDocumentManager.getInstance().getDocument(first)!!.text.contains("println(2)"))
        assertEquals(second, create(dir, text))

        // Another block of the page has a number of its own
        assertEquals("Shapes02a.kt", WriteCommandAction.writeCommandAction(project).compute<VirtualFile, Exception> {
            GuidePrograms.findOrCreate(dir, "Shapes02", text)
        }.name)
    }

    fun `test copies are not offered to version control`() {
        val dir = myFixture.tempDirFixture.findOrCreateDir("guide")
        val copy = create(dir, GuidePrograms.programText("fun main() {}"))
        val own = myFixture.addFileToProject("guide/MySketch.kt", "package guide\n").virtualFile
        val filter = GuidePrograms.VcsFilter()
        assertTrue(filter.isAdditionIgnored(project, VcsUtil.getFilePath(copy)))
        assertFalse("files the user makes are asked about as usual", filter.isAdditionIgnored(project, VcsUtil.getFilePath(own)))
    }

    fun `test the copy runs as a main class in the guide package`() {
        val file = myFixture.addFileToProject(
            "guide/Shapes01a.kt",
            GuidePrograms.programText("import kotlin.math.PI\n\nfun main() {\n    println(PI)\n}"),
        ).virtualFile
        assertEquals("guide.Shapes01aKt", RunSketchAction.mainClass(project, file))
    }

    private fun create(dir: VirtualFile, text: String): VirtualFile =
        WriteCommandAction.writeCommandAction(project).compute<VirtualFile, Exception> {
            GuidePrograms.findOrCreate(dir, "Shapes01", text)
        }

    private fun edit(file: VirtualFile, text: String) {
        WriteCommandAction.runWriteCommandAction(project) {
            FileDocumentManager.getInstance().getDocument(file)!!.setText(text)
        }
    }
}

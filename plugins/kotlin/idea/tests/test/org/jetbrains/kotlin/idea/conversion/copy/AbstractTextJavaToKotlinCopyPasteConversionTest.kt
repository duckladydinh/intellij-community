// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.conversion.copy

import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.util.ThrowableRunnable
import org.jetbrains.kotlin.idea.editor.KotlinEditorOptions
import org.jetbrains.kotlin.idea.test.InTextDirectivesUtils
import org.jetbrains.kotlin.idea.test.KotlinTestUtils
import org.jetbrains.kotlin.idea.test.KotlinWithJdkAndRuntimeLightProjectDescriptor
import org.jetbrains.kotlin.idea.test.runAll
import java.awt.datatransfer.StringSelection
import java.io.File

abstract class AbstractTextJavaToKotlinCopyPasteConversionTest : AbstractJ2kCopyPasteTest() {
    private var oldEditorOptions: KotlinEditorOptions? = null

    override fun getProjectDescriptor() = KotlinWithJdkAndRuntimeLightProjectDescriptor.INSTANCE

    override fun setUp() {
        super.setUp()
        oldEditorOptions = KotlinEditorOptions.getInstance().state
        KotlinEditorOptions.getInstance().isEnableJavaToKotlinConversion = true
        KotlinEditorOptions.getInstance().isDonTShowConversionDialog = true
    }

    override fun tearDown() {
        runAll(
            ThrowableRunnable { oldEditorOptions?.let { KotlinEditorOptions.getInstance().loadState(it) } },
            ThrowableRunnable { super.tearDown() }
        )
    }

    fun doTest(unused: String) {
        val fileName = fileName()
        myFixture.configureByFile(fileName)

        val fileText = dataFile().readText()
        val noConversionExpected = InTextDirectivesUtils.findListWithPrefixes(fileText, "// NO_CONVERSION_EXPECTED").isNotEmpty()

        // copy a file content directly to a buffer to keep it as is (keep original line endings etc)
        CopyPasteManager.getInstance().setContents(StringSelection(fileText))

        configureByDependencyIfExists(fileName.replace(".txt", ".dependency.kt"))
        configureByDependencyIfExists(fileName.replace(".txt", ".dependency.java"))

        configureTargetFile(fileName.replace(".txt", ".to.kt"))

        ConvertTextJavaCopyPasteProcessor.conversionPerformed = false

        myFixture.performEditorAction(IdeActions.ACTION_PASTE)

        kotlin.test.assertEquals(
            noConversionExpected, !ConvertTextJavaCopyPasteProcessor.conversionPerformed,
            if (noConversionExpected) "Conversion to Kotlin should not be suggested" else "No conversion to Kotlin suggested"
        )

        val testFile = dataFile()
        val expectedFile = File(testFile.parent, testFile.nameWithoutExtension + ".expected.kt")
        KotlinTestUtils.assertEqualsToFile(expectedFile, myFixture.file.text)
    }
}

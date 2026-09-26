package com.github.ljk0071.inlinecall

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.SmartPointerManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class InlineCallSettingsTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            InlineCallSettings.getInstance().loadState(InlineCallSettings.Options())
        } finally {
            super.tearDown()
        }
    }

    fun testDefaults() {
        val options = InlineCallSettings.getInstance().state
        assertEquals(30, options.maxLines)
        assertEquals(4, options.maxDepth)
    }

    fun testNewExpansionUsesSettings() {
        myFixture.configureByText(
            "A.java",
            """
            class A {
                int helper(int x) {
                    int y = x + 1;
                    return y;
                }
                int caller() { return helper(1); }
            }
            """.trimIndent(),
        )
        InlineCallSettings.getInstance().state.apply {
            maxLines = 2
            maxDepth = 0
        }
        val helper = (myFixture.file as PsiJavaFile).classes.single().findMethodsByName("helper", false).single()
        val editor = myFixture.editor
        val callEnd = editor.document.text.indexOf("helper(1)") + "helper(1)".length
        ExpandedCalls.expand(editor, TextRange(callEnd - "helper(1)".length, callEnd), CallTargets.body(helper)!!, 0)

        val renderer = editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength)
            .single().renderer as FunctionBodyRenderer
        assertEquals(listOf("int helper(int x) {", "    int y = x + 1;", "… (2 more lines)"), renderer.visibleText())
        // maxDepth = 0 이면 본문 안의 호출도 펼칠 수 없다.
        assertFalse(renderer.expand(renderer.root, BodyCall(0, "x", SmartPointerManager.createPointer<PsiElement>(helper)), CallTargets.body(helper)!!))
    }
}

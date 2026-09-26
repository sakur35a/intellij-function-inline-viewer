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
        assertTrue(options.mergeChains)
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
        ExpandedCalls.expand(editor, TextRange(callEnd - "helper(1)".length, callEnd), listOf(CallTargets.body(helper)!!), 0)

        val renderer = editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength)
            .single().renderer as FunctionBodyRenderer
        assertEquals(listOf("int helper(int x) {", "    int y = x + 1;", "… (2 more lines)"), renderer.visibleText())
        // maxDepth = 0 이면 본문 안의 호출도 펼칠 수 없다.
        assertFalse(renderer.expand(renderer.roots.single(), BodyCall(0, "x", listOf(SmartPointerManager.createPointer<PsiElement>(helper)), "x#1"), listOf(CallTargets.body(helper)!!)))
    }

    fun testLimitsApplyToAlreadyExpandedBody() {
        myFixture.configureByText(
            "A.java",
            """
            class A {
                int fact(int n) {
                    return n <= 1 ? 1 : n * fact(n - 1);
                }
                int v = fact(3);
            }
            """.trimIndent(),
        )
        val editor = myFixture.editor
        val end = editor.document.text.lastIndexOf("fact(3)") + "fact(3)".length
        val (range, methods) = CallTargets.hintAt(myFixture.file, end)!!
        ExpandedCalls.expand(editor, range, methods.map { CallTargets.body(it)!! }, 0)
        val renderer = editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength)
            .single().renderer as FunctionBodyRenderer
        // 두 단계 펼친다(depth 1, 2).
        repeat(2) {
            var node = renderer.roots.single()
            while (node.children.isNotEmpty()) node = node.children.values.single().single()
            val call = node.body.calls.single()
            assertTrue(renderer.expand(node, call, listOf(FunctionBody.of(call.targets.single().element!!)!!)))
        }
        assertEquals(9, renderer.visibleText().size)

        InlineCallSettings.getInstance().state.apply {
            maxLines = 2
            maxDepth = 1
        }
        ExpandedCalls.settingsChanged(recompute = false)

        // depth 2 는 접히고, 본문마다 2줄 + "… more" 만 남는다.
        assertEquals(
            listOf(
                "int fact(int n) {",
                "    return n <= 1 ? 1 : n * fact(n - 1);",
                "\tint fact(int n) {",
                "\t    return n <= 1 ? 1 : n * fact(n - 1);",
                "\t… (1 more lines)",
                "… (1 more lines)",
            ),
            renderer.visibleText(),
        )
    }

    fun testEnablingChainMergeCollapsesInnerChainExpansion() {
        InlineCallSettings.getInstance().state.mergeChains = false
        myFixture.configureByText(
            "B.java",
            """
            class B {
                B foo() { return this; }
                B bar() { return this; }
                void run() { foo().bar(); }
            }
            """.trimIndent(),
        )
        val editor = myFixture.editor
        val fooEnd = editor.document.text.lastIndexOf("foo()") + "foo()".length
        val (range, methods) = CallTargets.hintAt(myFixture.file, fooEnd)!!
        ExpandedCalls.expand(editor, range, methods.map { CallTargets.body(it)!! }, 0)
        assertEquals(1, ExpandedCalls.markerCount(editor))

        InlineCallSettings.getInstance().state.mergeChains = true
        ExpandedCalls.settingsChanged(recompute = true)
        ExpandedCalls.refreshNow(editor, force = true)

        // 합쳐진 뒤에는 foo() 끝에 힌트가 없으므로 그 위치의 펼침은 접힌다.
        assertEquals(0, ExpandedCalls.markerCount(editor))
    }
}

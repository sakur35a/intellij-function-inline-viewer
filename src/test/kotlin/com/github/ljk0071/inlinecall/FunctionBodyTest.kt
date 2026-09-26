package com.github.ljk0071.inlinecall

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.kotlin.psi.KtNamedFunction

class FunctionBodyTest : BasePlatformTestCase() {

    private fun javaMethod(name: String): PsiMethod =
        (myFixture.file as PsiJavaFile).classes.single().findMethodsByName(name, false).single()

    private fun FunctionBody.token(text: String): BodyToken =
        lines.flatMap { it.tokens }.first { it.text == text }

    fun testTokensMapToSourceAndKeywordsAreNotNavigable() {
        myFixture.configureByText(
            "A.java",
            "class A {\n\tint helper(int x) {\n\t\treturn x + 1;\n\t}\n}\n",
        )
        val body = CallTargets.body(javaMethod("helper"))!!
        val text = myFixture.editor.document.text

        // 탭 들여쓰기는 공통 들여쓰기만큼 빠지고 나머지는 공백 4칸으로 바뀐다.
        assertEquals(listOf("int helper(int x) {", "    return x + 1;", "}"), body.lines.map { it.text })
        for (token in body.lines.flatMap { it.tokens }) {
            if ('\t' !in text.substring(token.sourceOffset, token.sourceOffset + token.text.length)) {
                assertEquals(token.text, text.substring(token.sourceOffset, token.sourceOffset + token.text.length))
            }
        }
        assertFalse(body.token("return").isNavigable)
        assertTrue(body.token("helper").isNavigable)
        assertTrue("keyword should be highlighted", body.token("return").keys.isNotEmpty())
    }

    fun testNavigateToJavaCallee() {
        myFixture.configureByText(
            "A.java",
            """
            class A {
                int helper(int x) { return x; }
                int caller() {
                    return helper(1);
                }
            }
            """.trimIndent(),
        )
        val body = CallTargets.body(javaMethod("caller"))!!
        val helperCall = body.lines[1].tokens.first { it.text == "helper" }
        val target = CallTargets.navigationTarget(project, body, helperCall.sourceOffsetAt(2)) as OpenFileDescriptor
        assertEquals(javaMethod("helper").textOffset, target.offset)
    }

    fun testNavigateToKotlinCallee() {
        myFixture.configureByText(
            "A.kt",
            """
            fun helper(x: Int): Int = x

            fun caller(): Int {
                return helper(1)
            }
            """.trimIndent(),
        )
        val functions = PsiTreeUtil.findChildrenOfType(myFixture.file, KtNamedFunction::class.java)
        val caller = functions.single { it.name == "caller" }
        val helper = functions.single { it.name == "helper" }
        val body = FunctionBody.of(caller)!!
        val helperCall = body.lines[1].tokens.first { it.text == "helper" }
        val target = CallTargets.navigationTarget(project, body, helperCall.sourceOffset) as OpenFileDescriptor
        assertEquals(helper.textOffset, target.offset)
    }

    /** 펼친 뒤 원본이 수정되면 오프셋이 어긋나므로 이동하지 않는다. */
    fun testStaleBodyDoesNotNavigate() {
        myFixture.configureByText("A.java", "class A {\n    int helper() { return helper(); }\n}\n")
        val body = CallTargets.body(javaMethod("helper"))!!
        myFixture.type("// edit\n")
        assertNull(CallTargets.navigationTarget(project, body, body.token("helper").sourceOffset))
    }
}

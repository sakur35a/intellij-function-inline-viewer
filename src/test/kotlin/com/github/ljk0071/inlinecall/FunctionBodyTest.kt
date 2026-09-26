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

    fun testCollectsOnlyProjectCallsInBody() {
        myFixture.configureByText(
            "A.java",
            """
            class A {
                int helper(int x) { return x; }
                int caller(int y) {
                    System.out.println(y);
                    return helper(helper(y));
                }
            }
            """.trimIndent(),
        )
        val body = CallTargets.body(javaMethod("caller"))!!
        // 중첩 호출 helper(helper(y)) 는 각각의 호출 끝(안쪽 ")" 와 바깥 ")") 뒤에 힌트가 붙는다.
        val line = body.lines[2]
        assertEquals(listOf("helper(x)", "helper(x)"), line.calls.map { it.label })
        val prefixes = line.calls.map { call -> line.tokens.take(call.afterToken + 1).joinToString("") { it.text } }
        assertEquals(listOf("    return helper(helper(y)", "    return helper(helper(y))"), prefixes)
        assertTrue("JDK call must not get a hint", body.lines[1].calls.isEmpty())
    }

    fun testKotlinCallsInLambda() {
        myFixture.configureByText(
            "A.kt",
            """
            fun helper(x: Int): Int = x

            fun caller() {
                listOf(1).forEach { helper(it) }
            }
            """.trimIndent(),
        )
        val caller = PsiTreeUtil.findChildrenOfType(myFixture.file, KtNamedFunction::class.java).single { it.name == "caller" }
        val body = FunctionBody.of(caller)!!
        assertEquals(listOf("helper(x)"), body.lines[1].calls.map { it.label })
        assertInstanceOf(body.lines[1].calls.single().targets.single().element, KtNamedFunction::class.java)
    }

    fun testNestedExpandCollapseAndDepthLimit() {
        myFixture.configureByText(
            "A.java",
            """
            class A {
                int fact(int n) {
                    return n <= 1 ? 1 : n * fact(n - 1);
                }
            }
            """.trimIndent(),
        )
        val renderer = FunctionBodyRenderer(listOf(CallTargets.body(javaMethod("fact"))!!), indentPx = 0, maxDepth = 2)

        fun expandDeepest(): Boolean {
            var node = renderer.roots.single()
            while (node.children.isNotEmpty()) node = node.children.values.single().single()
            val call = node.body.lines[1].calls.single()
            return renderer.expand(node, call, listOf(FunctionBody.of(call.targets.single().element!!)!!))
        }

        assertTrue(expandDeepest())
        assertEquals(
            listOf(
                "int fact(int n) {",
                "    return n <= 1 ? 1 : n * fact(n - 1);",
                "\tint fact(int n) {",
                "\t    return n <= 1 ? 1 : n * fact(n - 1);",
                "\t}",
                "}",
            ),
            renderer.visibleText(),
        )
        assertTrue(expandDeepest())
        // depth 2 본문에서는 더 펼칠 수 없다(재귀 호출 무한 전개 방지).
        assertFalse(expandDeepest())
        assertEquals(9, renderer.visibleText().size)

        val root = renderer.roots.single()
        assertTrue(renderer.collapse(root, root.body.lines[1].calls.single()))
        assertEquals(3, renderer.visibleText().size)
    }

    /** 빈 줄(공백만 있는 줄 포함)이 있는 본문도 만들어져야 한다(예전엔 IllegalArgumentException: fromKey > toKey). */
    fun testBodyWithBlankLines() {
        myFixture.configureByText(
            "A.java",
            "class A {\n    int helper(int x) { return x; }\n    int caller() {\n        int a = helper(1);\n\n    \n        return helper(a);\n    }\n}\n",
        )
        val body = CallTargets.body(javaMethod("caller"))!!
        assertEquals(
            listOf("int caller() {", "    int a = helper(1);", "", "", "    return helper(a);", "}"),
            body.lines.map { it.text },
        )
        assertEquals(listOf("helper(x)", "helper(x)"), body.calls.map { it.label }.toList())
    }

    /** 한 줄에 호출이 여럿이면 호출마다 다른 색 번호를 주고, 호출된 이름 토큰에도 같은 번호를 준다. */
    fun testRainbowColorsForCallsOnSameLine() {
        myFixture.configureByText(
            "A.java",
            """
            class A {
                int add(int a, int b) { return a + b; }
                int mul(int a, int b) { return a * b; }
                int caller() {
                    int x = add(1, 2);
                    return add(mul(1, 2), 3);
                }
            }
            """.trimIndent(),
        )
        val body = CallTargets.body(javaMethod("caller"))!!
        val single = body.lines[1]
        assertNull(single.calls.single().color)
        assertTrue(single.tokenColors.isEmpty())

        val line = body.lines[2]
        val mul = line.calls.single { it.label.startsWith("mul") }
        val add = line.calls.single { it.label.startsWith("add") }
        assertEquals(0, mul.color) // 먼저 끝나는 호출부터 번호를 매긴다
        assertEquals(1, add.color)
        val colorOf = { name: String -> line.tokenColors[line.tokens.indexOfFirst { it.text == name }] }
        assertEquals(mul.color, colorOf("mul"))
        assertEquals(add.color, colorOf("add"))
    }
}

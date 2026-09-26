package com.github.ljk0071.inlinecall

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as Colors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import org.jetbrains.kotlin.psi.KtNamedFunction

class SemanticColorsTest : BasePlatformTestCase() {

    // java.util.List 가 resolve 되도록 mock JDK 를 붙인다.
    override fun getProjectDescriptor(): LightProjectDescriptor = LightJavaCodeInsightFixtureTestCase.JAVA_21

    override fun tearDown() {
        try {
            InlineCallSettings.getInstance().loadState(InlineCallSettings.Options())
        } finally {
            super.tearDown()
        }
    }

    /** 실제 코드처럼 백그라운드 읽기 작업에서 계산한다(Kotlin K2 분석은 EDT 금지). */
    private fun <T> inBackground(action: () -> T): T =
        ApplicationManager.getApplication().executeOnPooledThread<T> { ReadAction.compute<T, RuntimeException>(action) }.get()

    /** 각 식별자 토큰의 마지막(의미 분석) 키. 같은 텍스트가 여러 번 나오면 [occurrence] 번째. */
    private fun FunctionBody.semanticKey(text: String, occurrence: Int = 0): TextAttributesKey? =
        lines.flatMap { it.tokens }.filter { it.text == text }[occurrence].keys.lastOrNull()?.takeIf { it in semanticKeys }

    private val semanticKeys = setOf(
        Colors.FUNCTION_DECLARATION, Colors.FUNCTION_CALL, Colors.STATIC_METHOD, Colors.PARAMETER, Colors.LOCAL_VARIABLE,
        Colors.CONSTANT, Colors.STATIC_FIELD, Colors.INSTANCE_FIELD, Colors.CLASS_REFERENCE, Colors.INTERFACE_NAME,
    )

    fun testJavaIdentifiers() {
        myFixture.configureByText(
            "A.java",
            """
            import java.util.List;
            class A {
                static final int LIMIT = 3;
                static int counter;
                int size;
                static int twice(int v) { return v * 2; }
                int plus(int v) { return v + size; }
                int run(int param, List<String> list) {
                    int local = param + LIMIT + counter;
                    A other = new A();
                    return twice(local) + plus(local) + other.size + list.size();
                }
            }
            """.trimIndent(),
        )
        val run = (myFixture.file as PsiJavaFile).classes.single().findMethodsByName("run", false).single()
        val body = inBackground { CallTargets.body(run)!! }

        assertEquals(Colors.FUNCTION_DECLARATION, body.semanticKey("run"))
        assertEquals(Colors.PARAMETER, body.semanticKey("param"))
        assertEquals(Colors.PARAMETER, body.semanticKey("param", 1))
        assertEquals(Colors.INTERFACE_NAME, body.semanticKey("List"))
        assertEquals(Colors.LOCAL_VARIABLE, body.semanticKey("local"))
        assertEquals(Colors.LOCAL_VARIABLE, body.semanticKey("local", 1))
        assertEquals(Colors.CONSTANT, body.semanticKey("LIMIT"))
        assertEquals(Colors.STATIC_FIELD, body.semanticKey("counter"))
        assertEquals(Colors.CLASS_REFERENCE, body.semanticKey("A"))
        assertEquals(Colors.STATIC_METHOD, body.semanticKey("twice"))
        assertEquals(Colors.FUNCTION_CALL, body.semanticKey("plus"))
        assertEquals(Colors.INSTANCE_FIELD, body.semanticKey("size"))
        assertEquals(Colors.FUNCTION_CALL, body.semanticKey("size", 1))
    }

    fun testKotlinIdentifiers() {
        myFixture.configureByText(
            "A.kt",
            """
            class Box(val width: Int) {
                fun area(height: Int): Int = width * height
            }

            fun helper(x: Int): Int = x

            fun run(box: Box): Int {
                val local = helper(box.width)
                return box.area(local)
            }
            """.trimIndent(),
        )
        val run = PsiTreeUtil.findChildrenOfType(myFixture.file, KtNamedFunction::class.java).single { it.name == "run" }
        val body = inBackground { FunctionBody.of(run)!! }

        assertEquals(Colors.FUNCTION_DECLARATION, body.semanticKey("run"))
        assertEquals(Colors.PARAMETER, body.semanticKey("box"))
        assertEquals(Colors.CLASS_REFERENCE, body.semanticKey("Box"))
        assertEquals(Colors.LOCAL_VARIABLE, body.semanticKey("local"))
        // Kotlin 최상위 함수는 static 이 아니라 일반 함수 호출 색이다.
        assertEquals(Colors.FUNCTION_CALL, body.semanticKey("helper"))
        assertEquals(Colors.FUNCTION_CALL, body.semanticKey("area"))
        assertEquals(Colors.INSTANCE_FIELD, body.semanticKey("width"))
    }

    fun testOnlyVisibleLinesAreResolved() {
        InlineCallSettings.getInstance().state.maxLines = 1
        myFixture.configureByText(
            "A.java",
            "class A {\n    int run(int param) {\n        return param;\n    }\n}\n",
        )
        val run = (myFixture.file as PsiJavaFile).classes.single().findMethodsByName("run", false).single()
        val body = inBackground { CallTargets.body(run)!! }
        assertEquals(Colors.PARAMETER, body.semanticKey("param"))
        assertNull(body.semanticKey("param", 1))
    }
}

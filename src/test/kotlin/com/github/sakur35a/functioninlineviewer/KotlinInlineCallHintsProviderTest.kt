package com.github.sakur35a.functioninlineviewer

import com.intellij.codeInsight.hints.declarative.InlayHintsProviderFactory
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.lang.java.JavaLanguage
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.utils.inlays.declarative.DeclarativeInlayHintsProviderTestCase
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNamedFunction

class KotlinInlineCallHintsProviderTest : DeclarativeInlayHintsProviderTestCase() {

    /** optional dependency 설정 파일(inline-call-kotlin.xml)이 실제로 로드되는지 확인 */
    fun testRegisteredForJavaAndKotlin() {
        for (language in listOf(JavaLanguage.INSTANCE, KotlinLanguage.INSTANCE)) {
            assertNotNull(
                language.id,
                InlayHintsProviderFactory.getProviderInfo(language, InlineCallHintsProvider.PROVIDER_ID),
            )
        }
    }

    fun testTopLevelExtensionAndLambda() {
        myFixture.addFileToProject(
            "demo/Util.kt",
            """
            package demo

            fun add(a: Int, b: Int): Int = a + b

            fun Int.twice(): Int {
                return this * 2
            }

            fun runBlock(block: () -> Unit) {
                block()
            }
            """.trimIndent()
        )
        doTestProvider(
            "Main.kt",
            """
            package demo

            fun main() {
                val sum = add(1, 2)/*<# ▶ |add(a, b) #>*/
                val t = sum.twice()/*<# ▶ |twice() #>*/
                runBlock {
                    add(3, 4)/*<# ▶ |add(a, b) #>*/
                }/*<# ▶ |runBlock(block) #>*/
            }
            """.trimIndent(),
            InlineCallHintsProvider(),
        )
    }

    fun testJavaMethodFromKotlin() {
        myFixture.addFileToProject(
            "demo/MathUtil.java",
            """
            package demo;
            public class MathUtil {
                public static int add(int a, int b) { return a + b; }
            }
            """.trimIndent()
        )
        doTestProvider(
            "Main.kt",
            """
            package demo

            fun main() {
                MathUtil.add(1, 2)/*<# ▶ |add(a, b) #>*/
            }
            """.trimIndent(),
            InlineCallHintsProvider(),
        )
    }

    /** light method 로 resolve 되더라도 본문은 KtNamedFunction 원문(KDoc 제외)이어야 한다. */
    fun testBodyLinesFromKtNamedFunction() {
        myFixture.configureByText(
            "Main.kt",
            """
            /** doc */
            fun Int.twice(): Int {
                return this * 2
            }

            fun main() {
                1.twice()
            }
            """.trimIndent()
        )
        val ktCall = PsiTreeUtil.findChildrenOfType(myFixture.file, KtCallExpression::class.java)
            .single { it.text == "twice()" }
        val call = CallTargets.toCall(ktCall)!!
        val method: PsiMethod = CallTargets.resolveProjectMethod(call)!!
        assertInstanceOf(CallTargets.declarationOf(method), KtNamedFunction::class.java)
        assertEquals(
            listOf("fun Int.twice(): Int {", "    return this * 2", "}"),
            CallTargets.bodyLines(method),
        )
    }

    /** Kotlin 프로퍼티 접근은 get()/set() 을 직접 작성했을 때만 힌트(읽기는 getter, 대입은 setter). */
    fun testPropertyAccessWithWrittenAccessors() {
        myFixture.addFileToProject(
            "demo/T.kt",
            """
            package demo
            class T(var celsius: Double) {
                var fahrenheit: Double
                    get() = celsius * 9 / 5 + 32
                    set(value) { celsius = (value - 32) * 5 / 9 }
                val plain: Int = 1
                var onlyGetter: Int = 0
                    get() = field + 1
            }
            """.trimIndent(),
        )
        doTestProvider(
            "Main.kt",
            """
            package demo

            fun m(t: T) {
                val a = t.fahrenheit/*<# ▶ |fahrenheit #>*/
                t.fahrenheit/*<# ▶ |set fahrenheit #>*/ = 100.0
                t.fahrenheit/*<# ▶ |set fahrenheit #>*/ += 1.0
                val b = t.plain
                t.celsius = 2.0
                val c = t.onlyGetter/*<# ▶ |onlyGetter #>*/
                t.onlyGetter = 3
                val fahrenheit = 1
                println(fahrenheit)
            }
            """.trimIndent(),
            InlineCallHintsProvider(),
        )
    }

    fun testPropertyAccessExpandsDeclaration() {
        myFixture.addFileToProject(
            "demo/T.kt",
            """
            package demo
            class T(var celsius: Double) {
                val fahrenheit: Double
                    get() = celsius * 9 / 5 + 32
            }
            """.trimIndent(),
        )
        myFixture.configureByText("Main.kt", "package demo\n\nfun m(t: T) = t.fahrenheit\n")
        val end = myFixture.editor.document.text.lastIndexOf("t.fahrenheit") + "t.fahrenheit".length
        val bodies = ApplicationManager.getApplication().executeOnPooledThread<List<FunctionBody>> {
            ReadAction.compute<List<FunctionBody>, RuntimeException> {
                CallTargets.hintAt(myFixture.file, end)!!.methods.map { CallTargets.body(it)!! }
            }
        }.get()
        assertEquals(listOf("val fahrenheit: Double", "    get() = celsius * 9 / 5 + 32"), bodies.single().lines.map { it.text })
        val names = ApplicationManager.getApplication().executeOnPooledThread<List<String>> {
            ReadAction.compute<List<String>, RuntimeException> {
                CallTargets.hintAt(myFixture.file, end)!!.names.map { it.substring(myFixture.editor.document.text) }
            }
        }.get()
        assertEquals(listOf("fahrenheit"), names)
    }
}

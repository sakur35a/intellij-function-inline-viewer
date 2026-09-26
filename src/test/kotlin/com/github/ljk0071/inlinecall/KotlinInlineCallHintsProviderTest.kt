package com.github.ljk0071.inlinecall

import com.intellij.codeInsight.hints.declarative.InlayHintsProviderFactory
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
                val sum = add(1, 2)/*<# ▶ |add|(a, b) #>*/
                val t = sum.twice()/*<# ▶ |twice|() #>*/
                runBlock {
                    add(3, 4)/*<# ▶ |add|(a, b) #>*/
                }/*<# ▶ |runBlock|(block) #>*/
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
                MathUtil.add(1, 2)/*<# ▶ |add|(a, b) #>*/
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
}

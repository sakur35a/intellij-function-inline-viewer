package com.github.sakur35a.functioninlineviewer

import com.intellij.psi.PsiJavaFile
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.testFramework.utils.inlays.declarative.DeclarativeInlayHintsProviderTestCase

class InlineCallHintsProviderTest : DeclarativeInlayHintsProviderTestCase() {

    // JDK 호출(System.out.println)이 실제로 resolve 되도록 mock JDK 를 붙인다.
    override fun getProjectDescriptor(): LightProjectDescriptor = LightJavaCodeInsightFixtureTestCase.JAVA_21

    fun testProjectMethodOnly() {
        myFixture.addFileToProject(
            "demo/MathUtil.java",
            """
            package demo;
            public class MathUtil {
                /** doc */
                public static int add(int a, int b) { return a + b; }
            }
            """.trimIndent()
        )
        doTestProvider(
            "Main.java",
            """
            package demo;
            public class Main {
                public static void main(String[] args) {
                    int sum = MathUtil.add(1, 2)/*<# ▶ |add(a, b) #>*/;
                    System.out.println(sum);
                }
            }
            """.trimIndent(),
            InlineCallHintsProvider(),
        )
    }

    fun testBodyLinesSkipDocComment() {
        myFixture.configureByText(
            "MathUtil.java",
            """
            public class MathUtil {
                /** doc */
                public static int add(int a, int b) {
                    return a + b;
                }
            }
            """.trimIndent()
        )
        val method = (myFixture.file as PsiJavaFile).classes.single().findMethodsByName("add", false).single()
        assertEquals(
            listOf("public static int add(int a, int b) {", "    return a + b;", "}"),
            CallTargets.bodyLines(method),
        )
    }
}

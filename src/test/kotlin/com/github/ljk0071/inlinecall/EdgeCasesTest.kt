package com.github.ljk0071.inlinecall

import com.intellij.codeInsight.hints.declarative.impl.inlayRenderer.DeclarativeInlayRendererBase
import com.intellij.codeInsight.hints.declarative.impl.views.TextInlayPresentationEntry
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.testFramework.utils.inlays.declarative.DeclarativeInlayHintsProviderTestCase

class EdgeCasesTest : DeclarativeInlayHintsProviderTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = LightJavaCodeInsightFixtureTestCase.JAVA_21

    override fun tearDown() {
        try {
            InlineCallSettings.getInstance().loadState(InlineCallSettings.Options())
        } finally {
            super.tearDown()
        }
    }

    private val chainClass = """
        package demo;
        public class B {
            public B foo() { return this; }
            public B bar(int x) { return this; }
            public String name() { return "b"; }
        }
    """.trimIndent()

    fun testRecursiveCallHasHint() {
        doTestProvider(
            "R.java",
            """
            class R {
                int fact(int n) {
                    return n <= 1 ? 1 : n * fact(n - 1)/*<# ▶ |fact(n) #>*/;
                }
            }
            """.trimIndent(),
            InlineCallHintsProvider(),
        )
    }

    fun testOverloadsShowTypesAndResolveExactly() {
        doTestProvider(
            "O.java",
            """
            class O {
                int add(int a, int b) { return a + b; }
                double add(double a, double b) { return a + b; }
                int single(int value) { return value; }
                void run() {
                    add(1, 2)/*<# ▶ |add(int, int) #>*/;
                    add(1.0, 2.0)/*<# ▶ |add(double, double) #>*/;
                    single(1)/*<# ▶ |single(value) #>*/;
                }
            }
            """.trimIndent(),
            InlineCallHintsProvider(),
        )
    }

    fun testSameLineChainIsMerged() {
        myFixture.addFileToProject("demo/B.java", chainClass)
        doTestProvider(
            "Main.java",
            """
            package demo;
            class Main {
                void run(B b) {
                    b.foo().bar(1)/*<# ▶ |foo() → bar(x) #>*/;
                    b.foo().name().trim()/*<# ▶ |foo() → name() #>*/;
                    "x".trim().length();
                    b.foo()/*<# ▶ |foo() #>*/
                        .bar(2)/*<# ▶ |bar(x) #>*/;
                }
            }
            """.trimIndent(),
            InlineCallHintsProvider(),
        )
    }

    fun testChainMergeCanBeDisabled() {
        InlineCallSettings.getInstance().state.mergeChains = false
        myFixture.addFileToProject("demo/B.java", chainClass)
        doTestProvider(
            "Main.java",
            """
            package demo;
            class Main {
                void run(B b) {
                    b.foo()/*<# ▶ |foo() #>*/.bar(1)/*<# ▶ |bar(x) #>*/;
                }
            }
            """.trimIndent(),
            InlineCallHintsProvider(),
        )
    }

    fun testKotlinSameLineChainIsMerged() {
        myFixture.addFileToProject(
            "demo/K.kt",
            """
            package demo
            class K {
                fun foo(): K = this
                fun bar(x: Int): K = this
            }
            fun K.ext(): K = this
            """.trimIndent(),
        )
        doTestProvider(
            "Main.kt",
            """
            package demo
            fun run(k: K?) {
                k?.foo()?.bar(1)?.ext()/*<# ▶ |foo() → bar(x) → ext() #>*/
            }
            """.trimIndent(),
            InlineCallHintsProvider(),
        )
    }

    fun testAbstractMethodShowsDeclarationOnly() {
        myFixture.configureByText(
            "S.java",
            """
            interface S {
                /** doc */
                int size();
            }
            """.trimIndent(),
        )
        val method = (myFixture.file as PsiJavaFile).classes.single().methods.single()
        val body = CallTargets.body(method)!!
        assertFalse(body.hasBody)
        val renderer = FunctionBodyRenderer(listOf(body), indentPx = 0)
        assertEquals(listOf("int size();", InlineCallBundle.message("body.no.implementations")), renderer.visibleText())
    }

    fun testInterfaceMethodListsImplementationsAndExpandsThem() {
        myFixture.addFileToProject(
            "demo/Shapes.java",
            """
            package demo;
            interface Shape { double area(); }
            abstract class Base implements Shape { public abstract double area(); }
            class Circle extends Base { public double area() { return 3.0; } }
            class Square implements Shape { public double area() { return 4.0; } }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "demo/Tri.kt",
            """
            package demo
            class Tri : Shape {
                override fun area(): Double = 0.5
            }
            """.trimIndent(),
        )
        myFixture.configureByText("Main.java", "package demo;\nclass Main {\n    double v(Shape s) { return s.area(); }\n}\n")
        // Kotlin(K2) 구현체 검색은 EDT 에서 금지되므로 실제 코드처럼 백그라운드 읽기 작업에서 계산한다.
        val end = myFixture.editor.document.text.lastIndexOf("area()") + "area()".length
        val (range, bodies) = inBackground {
            val (range, methods) = CallTargets.hintAt(myFixture.file, end)!!
            range to methods.map { CallTargets.body(it)!! }
        }
        ExpandedCalls.expand(myFixture.editor, range, bodies, 0)
        val renderer = renderers().single()
        val root = renderer.roots.single()
        assertFalse(root.body.hasBody)

        // 본문이 있는 구현체만 나온다(추상 Base 는 빠짐). 검색 순서는 보장되지 않으므로 정렬해서 비교한다.
        val lines = renderer.visibleText()
        assertEquals("double area();", lines[0])
        assertEquals(InlineCallBundle.message("body.implementations"), lines[1])
        val labels = root.body.calls.map { it.label }.sorted().toList()
        assertEquals(listOf("Circle.area()", "Square.area()", "Tri.area()"), labels)

        // 구현체 힌트를 펼치면 그 본문이 한 단계 아래에 보인다(Kotlin 구현체 포함).
        val tri = root.body.calls.single { it.label == "Tri.area()" }
        assertTrue(renderer.expand(root, tri, inBackground { tri.targets.map { FunctionBody.of(it.element!!)!! } }))
        assertTrue(renderer.visibleText().contains("\toverride fun area(): Double = 0.5"))
    }

    fun testDefaultMethodOffersLazyOverridesSearch() {
        val procFile = myFixture.addFileToProject(
            "demo/Proc.java",
            """
            package demo;
            interface Proc {
                default Object before(Object bean) {
                    return bean;
                }
            }
            class Wrap implements Proc { public Object before(Object bean) { return "w"; } }
            class Plain implements Proc {}
            """.trimIndent(),
        )
        myFixture.configureByText("Main.java", "package demo;\nclass Main {\n    Object v(Proc p) { return p.before(1); }\n}\n")
        val end = myFixture.editor.document.text.lastIndexOf("before(1)") + "before(1)".length
        val (range, bodies) = inBackground {
            val (range, methods) = CallTargets.hintAt(myFixture.file, end)!!
            range to methods.map { CallTargets.body(it)!! }
        }
        ExpandedCalls.expand(myFixture.editor, range, bodies, 0)
        val renderer = renderers().single()
        val root = renderer.roots.single()
        assertEquals(
            listOf("default Object before(Object bean) {", "    return bean;", "}", InlineCallBundle.message("body.overrides")),
            renderer.visibleText(),
        )

        // "find" 를 펼칠 때 처음으로 재정의를 검색한다. 재정의하지 않은 Plain 은 빠진다.
        val find = root.body.calls.single()
        assertTrue(find.searchesOverrides)
        assertTrue(renderer.expand(root, find, inBackground { find.targets.mapNotNull { it.element?.let(find::load) } }))
        val overrides = root.children.getValue(find).single()
        assertEquals(listOf("Wrap.before(bean)"), overrides.body.calls.map { it.label }.toList())

        // 원본이 바뀌어 다시 계산돼도 재정의 목록 펼침은 유지된다.
        val proc = PsiDocumentManager.getInstance(project).getDocument(procFile)!!
        edit { proc.insertString(proc.text.indexOf("return bean;"), "// edited\n        ") }
        ExpandedCalls.refreshNow(myFixture.editor)
        val refreshed = renderers().single().roots.single()
        assertEquals(listOf("Wrap.before(bean)"), refreshed.children.values.single().single().body.calls.map { it.label }.toList())
    }

    fun testNoOverridesLineWithoutInheritors() {
        myFixture.configureByText(
            "A.java",
            "class A {\n    int helper() { return 1; }\n    int v = helper();\n}\n",
        )
        val renderer = expandCallEndingWith("helper()")
        assertEquals(listOf("int helper() { return 1; }"), renderer.visibleText())
    }

    fun testImplementationLinesHiddenAtMaxDepth() {
        myFixture.configureByText(
            "S.java",
            """
            interface S { int size(); }
            class Impl implements S { public int size() { return 1; } }
            """.trimIndent(),
        )
        val method = (myFixture.file as PsiJavaFile).classes.first().methods.single()
        val renderer = FunctionBodyRenderer(listOf(CallTargets.body(method)!!), indentPx = 0, maxDepth = 0)
        assertEquals(listOf("int size();", InlineCallBundle.message("body.implementations")), renderer.visibleText())
    }

    fun testMergedChainExpandsAllBodies() {
        myFixture.addFileToProject("demo/B.java", chainClass)
        myFixture.configureByText("Main.java", "package demo;\nclass Main {\n    void run(B b) { b.foo().bar(1); }\n}\n")
        val renderer = expandCallEndingWith("bar(1)")
        assertEquals(
            listOf("public B foo() { return this; }", "public B bar(int x) { return this; }"),
            renderer.visibleText(),
        )
    }

    private fun <T> inBackground(action: () -> T): T =
        ApplicationManager.getApplication().executeOnPooledThread<T> { ReadAction.compute<T, RuntimeException>(action) }.get()

    // ---- 갱신 / 정리 ----

    private fun expandCallEndingWith(callText: String): FunctionBodyRenderer {
        val editor = myFixture.editor
        val end = editor.document.text.lastIndexOf(callText) + callText.length
        val (range, methods) = CallTargets.hintAt(myFixture.file, end)!!
        ExpandedCalls.expand(editor, range, methods.map { CallTargets.body(it)!! }, 0)
        return renderers().single()
    }

    private fun renderers(): List<FunctionBodyRenderer> =
        myFixture.editor.inlayModel.getBlockElementsInRange(0, myFixture.editor.document.textLength)
            .map { it.renderer as FunctionBodyRenderer }

    private fun edit(action: () -> Unit) {
        WriteCommandAction.runWriteCommandAction(project, action)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    fun testBodyRefreshesWhenTargetChangesAndKeepsNestedExpansion() {
        val util = myFixture.addFileToProject(
            "demo/U.java",
            """
            package demo;
            public class U {
                public static int outer(int x) {
                    return inner(x);
                }
                public static int inner(int y) {
                    return y;
                }
            }
            """.trimIndent(),
        )
        myFixture.configureByText("Main.java", "package demo;\nclass Main {\n    int v = U.outer(1);\n}\n")
        val renderer = expandCallEndingWith("outer(1)")
        val root = renderer.roots.single()
        val innerCall = root.body.calls.single()
        assertTrue(renderer.expand(root, innerCall, innerCall.targets.map { FunctionBody.of(it.element!!)!! }))

        // 다른 파일의 대상 함수 본문을 고친다.
        val document = PsiDocumentManager.getInstance(project).getDocument(util)!!
        edit { document.replaceString(document.text.indexOf("return y;"), document.text.indexOf("return y;") + "return y;".length, "return y + 1;") }
        ExpandedCalls.refreshNow(myFixture.editor)

        assertEquals(
            listOf(
                "public static int outer(int x) {",
                "    return inner(x);",
                "\tpublic static int inner(int y) {",
                "\t    return y + 1;",
                "\t}",
                "}",
            ),
            renderers().single().visibleText(),
        )
        assertTrue("fresh body must allow navigation", renderers().single().roots.single().body.isUpToDate())
    }

    /** 에디터에 실제로 그려진 이 플러그인 힌트의 텍스트(테스트 전용으로 플랫폼 내부 렌더러를 읽는다). */
    private fun hintTexts(): List<String> =
        myFixture.editor.inlayModel.getInlineElementsInRange(0, myFixture.editor.document.textLength)
            .mapNotNull { it.renderer as? DeclarativeInlayRendererBase<*> }
            .filter { it.providerId == InlineCallHintsProvider.PROVIDER_ID }
            .map { renderer ->
                // getEntries() 는 바이트코드상 public 이지만 Kotlin 메타데이터가 private 이라 리플렉션으로 읽는다.
                renderer.presentationLists.flatMap { (it.javaClass.getMethod("getEntries").invoke(it) as Array<*>).toList() }
                    .filterIsInstance<TextInlayPresentationEntry>().joinToString("") { it.text }
            }

    /** 펼침/접힘/자동 접힘 뒤 힌트가 다시 수집되어 ▶/▼ 가 실제 상태를 따라가야 한다. */
    fun testArrowFollowsExpansionState() {
        myFixture.configureByText("A.java", "class A {\n    int helper() { return 1; }\n    int v = helper();\n}\n")
        myFixture.doHighlighting()
        assertEquals(listOf("▶ helper()"), hintTexts())

        expandCallEndingWith("helper()")
        myFixture.doHighlighting()
        assertEquals(listOf("▼ helper()"), hintTexts())

        val end = myFixture.editor.document.text.lastIndexOf("helper()") + "helper()".length
        assertTrue(ExpandedCalls.collapse(myFixture.editor, end))
        myFixture.doHighlighting()
        assertEquals(listOf("▶ helper()"), hintTexts())

        // 다시 펼친 뒤 대상이 사라져 자동으로 접혀도 ▶ 로 돌아온다.
        expandCallEndingWith("helper()")
        myFixture.doHighlighting()
        val document = myFixture.editor.document
        edit { document.replaceString(document.text.indexOf("int helper()"), document.text.indexOf("int helper()") + "int helper()".length, "int other()") }
        ExpandedCalls.refreshNow(myFixture.editor)
        edit { document.replaceString(document.text.indexOf("int other()"), document.text.indexOf("int other()") + "int other()".length, "int helper()") }
        myFixture.doHighlighting()
        assertEquals(0, ExpandedCalls.markerCount(myFixture.editor))
        assertEquals(listOf("▶ helper()"), hintTexts())
    }

    fun testDeletedCallRemovesInlayAndMarker() {
        myFixture.configureByText(
            "A.java",
            "class A {\n    int helper() { return 1; }\n    int v = helper();\n}\n",
        )
        expandCallEndingWith("helper();".dropLast(1))
        assertEquals(1, ExpandedCalls.markerCount(myFixture.editor))

        val document = myFixture.editor.document
        edit { document.replaceString(document.text.indexOf("helper();"), document.text.indexOf("helper();") + "helper()".length, "42") }
        ExpandedCalls.refreshNow(myFixture.editor)

        assertEquals(0, ExpandedCalls.markerCount(myFixture.editor))
        assertTrue(renderers().isEmpty())
    }

    fun testCallRetargetedToLibraryCollapses() {
        myFixture.configureByText(
            "A.java",
            "class A {\n    String helper() { return \"\"; }\n    String v = helper();\n}\n",
        )
        expandCallEndingWith("helper();".dropLast(1))
        val document = myFixture.editor.document
        val start = document.text.indexOf("helper();")
        edit { document.replaceString(start, start + "helper()".length, "String.valueOf(1)") }
        ExpandedCalls.refreshNow(myFixture.editor)
        assertEquals(0, ExpandedCalls.markerCount(myFixture.editor))
    }

    fun testReleasingEditorDisposesExpansions() {
        myFixture.configureByText("A.java", "class A {\n    int helper() { return 1; }\n    int v = helper();\n}\n")
        val editor = EditorFactory.getInstance().createEditor(myFixture.editor.document, project)
        try {
            val end = editor.document.text.indexOf("helper();") + "helper()".length
            val (range, methods) = CallTargets.hintAt(myFixture.file, end)!!
            ExpandedCalls.expand(editor, range, methods.map { CallTargets.body(it)!! }, 0)
            assertEquals(1, ExpandedCalls.markerCount(editor))
            assertTrue(ExpandedCalls.isExpanded(editor, end))
        } finally {
            EditorFactory.getInstance().releaseEditor(editor)
        }
        assertEquals(0, ExpandedCalls.markerCount(editor))
        assertFalse(ExpandedCalls.isExpanded(editor, TextRange(0, 0).endOffset))
    }
}

package com.github.ljk0071.inlinecall

import com.intellij.codeInsight.hints.declarative.impl.inlayRenderer.DeclarativeInlayRendererBase
import com.intellij.openapi.application.impl.NonBlockingReadActionImpl
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import java.awt.Point
import java.awt.event.MouseEvent
import com.intellij.testFramework.PlatformTestUtil
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

    private fun ourHint(): Inlay<*> =
        myFixture.editor.inlayModel.getInlineElementsInRange(0, myFixture.editor.document.textLength)
            .single { (it.renderer as? DeclarativeInlayRendererBase<*>)?.providerId == InlineCallHintsProvider.PROVIDER_ID }

    private fun waitForExpansion() {
        NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    /** 본문은 사용자가 보는 화살표(플랫폼 토글)를 따라간다. 화살표가 토글되지 않은 클릭(여백, Cmd+클릭)은 본문도 그대로. */
    fun testBodyFollowsDisplayedArrow() {
        myFixture.configureByText("A.java", "class A {\n    int helper() { return 1; }\n    int v = helper();\n}\n")
        myFixture.doHighlighting()
        val listener = InlineCallMouseListener()
        assertEquals(false, DeclarativeHint.isExpanded(ourHint()))

        // 화살표는 ▶ 그대로인데 본문만 펼쳐진 어긋난 상태 -> 맞춰서 접는다.
        expandCallEndingWith("helper()")
        listener.syncWithHint(myFixture.editor, ourHint())
        assertEquals(0, ExpandedCalls.markerCount(myFixture.editor))

        // 화살표 ▶ 이고 본문도 접혀 있으면(토글 안 된 클릭) 아무것도 하지 않는다.
        listener.syncWithHint(myFixture.editor, ourHint())
        waitForExpansion()
        assertEquals(0, ExpandedCalls.markerCount(myFixture.editor))

        // 펼친 상태에서 힌트가 새로 수집되면 처음 상태가 ▼ 이다.
        expandCallEndingWith("helper()")
        edit { myFixture.editor.document.insertString(0, " ") }
        myFixture.doHighlighting()
        assertEquals(true, DeclarativeHint.isExpanded(ourHint()))

        // 화살표 ▼ 인데 본문이 없으면 펼친다.
        val end = myFixture.editor.document.text.lastIndexOf("helper()") + "helper()".length
        assertTrue(ExpandedCalls.collapse(myFixture.editor, end))
        listener.syncWithHint(myFixture.editor, ourHint())
        waitForExpansion()
        assertEquals(1, ExpandedCalls.markerCount(myFixture.editor))
    }

    /** 플랫폼 클릭 처리로 화살표를 실제로 토글한 뒤 본문이 따라오는지. 여백 클릭은 토글도 본문 변화도 없다. */
    fun testPlatformToggleDrivesBody() {
        myFixture.configureByText("A.java", "class A {\n    int helper() { return 1; }\n    int v = helper();\n}\n")
        myFixture.doHighlighting()
        val listener = InlineCallMouseListener()
        val editor = myFixture.editor

        fun platformClick(x: Int) {
            val hint = ourHint()
            val bounds = hint.bounds!!
            val mouse = MouseEvent(editor.contentComponent, MouseEvent.MOUSE_CLICKED, 0, 0, bounds.x + x, bounds.y + 2, 1, false, MouseEvent.BUTTON1)
            val event = EditorMouseEvent(editor, mouse, EditorMouseEventArea.EDITING_AREA)
            (hint.renderer as DeclarativeInlayRendererBase<*>).handleLeftClick(event, Point(x, 2), false)
            listener.syncWithHint(editor, hint)
            waitForExpansion()
        }

        val middle = ourHint().widthInPixels / 2
        // 손가락 커서 판정은 플랫폼이 토글하는 영역과 같아야 한다.
        assertEquals(true, DeclarativeHint.isOverText(ourHint(), Point(middle, 2)))
        assertEquals(false, DeclarativeHint.isOverText(ourHint(), Point(0, 2)))

        platformClick(middle)
        assertEquals(true, DeclarativeHint.isExpanded(ourHint()))
        assertEquals(1, ExpandedCalls.markerCount(editor))

        platformClick(0) // 왼쪽 여백: 플랫폼이 토글하지 않는다
        assertEquals(true, DeclarativeHint.isExpanded(ourHint()))
        assertEquals(1, ExpandedCalls.markerCount(editor))

        platformClick(middle)
        assertEquals(false, DeclarativeHint.isExpanded(ourHint()))
        assertEquals(0, ExpandedCalls.markerCount(editor))
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

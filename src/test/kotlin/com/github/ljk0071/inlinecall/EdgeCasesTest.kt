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
import com.intellij.util.ui.JBUI
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

    /**
     * 컴파일러가 만든 메서드 중 enum values()/valueOf() 만 힌트를 붙이고(펼치면 상수 목록),
     * record 암묵 접근자는 소스에 본문이 없으므로 붙이지 않는다(직접 작성한 접근자는 붙는다).
     */
    fun testSyntheticJavaMethods() {
        doTestProvider(
            "E.java",
            """
            enum E {
                A;
                static void m(R r) {
                    E.values()/*<# ▶ |values() #>*/;
                    E.valueOf("A")/*<# ▶ |valueOf(name) #>*/;
                    r.x();
                    r.y()/*<# ▶ |y() #>*/;
                }
            }
            record R(int x, int y) {
                public int y() { return y; }
            }
            """.trimIndent(),
            InlineCallHintsProvider(),
        )
    }

    fun testEnumValuesShowsConstants() {
        myFixture.configureByText(
            "E.java",
            """
            enum E {
                RED("r"),
                GREEN("g");
                E(String s) {}
                static void m() { E.values(); }
            }
            """.trimIndent(),
        )
        val renderer = expandCallEndingWith("E.values()")
        assertEquals(
            listOf(InlineCallBundle.message("body.enum.constants", "E"), "RED(\"r\"),", "GREEN(\"g\")"),
            renderer.visibleText(),
        )
    }

    fun testKotlinEnumValuesShowsConstants() {
        myFixture.configureByText(
            "K.kt",
            """
            enum class Color(val code: String) {
                RED("r"),
                GREEN("g");
            }
            fun m() = Color.values()
            """.trimIndent(),
        )
        val end = myFixture.editor.document.text.lastIndexOf("Color.values()") + "Color.values()".length
        val (range, bodies) = inBackground {
            val (range, methods) = CallTargets.hintAt(myFixture.file, end)!!
            range to methods.map { CallTargets.body(it)!! }
        }
        ExpandedCalls.expand(myFixture.editor, range, bodies, 0)
        assertEquals(
            // Kotlin 은 마지막 enum 항목 범위에 ';' 까지 포함된다(원문 그대로).
            listOf(InlineCallBundle.message("body.enum.constants", "Color"), "RED(\"r\"),", "GREEN(\"g\");"),
            renderers().single().visibleText(),
        )
    }

    /** Kotlin 은 get()/set() 을 직접 작성한 접근자에만 힌트를 붙이고, 펼치면 프로퍼티 선언을 보여준다. */
    fun testKotlinMembersOnlyWithWrittenAccessors() {
        myFixture.addFileToProject(
            "demo/D.kt",
            """
            package demo
            data class D(val a: Int) {
                var b: Int = 0
                val c: Int get() = a + 1
                var d: Int = 0
                    set(v) { field = v * 2 }
                fun f(): Int = a
            }
            """.trimIndent(),
        )
        doTestProvider(
            "Main.java",
            """
            package demo;
            class Main {
                void m(D d) {
                    d.copy(1);
                    d.component1();
                    d.getA();
                    d.getB();
                    d.setB(2);
                    d.getC()/*<# ▶ |getC() #>*/;
                    d.getD();
                    d.setD(3)/*<# ▶ |setD(v) #>*/;
                    d.f()/*<# ▶ |f() #>*/;
                }
            }
            """.trimIndent(),
            InlineCallHintsProvider(),
        )
    }

    fun testKotlinWrittenGetterShowsProperty() {
        myFixture.addFileToProject(
            "demo/D.kt",
            """
            package demo
            class D(val a: Int) {
                /** doc */
                val c: Int
                    get() = a + 1
            }
            """.trimIndent(),
        )
        myFixture.configureByText("Main.java", "package demo;\nclass Main {\n    int m(D d) { return d.getC(); }\n}\n")
        val end = myFixture.editor.document.text.lastIndexOf("getC()") + "getC()".length
        val (range, bodies) = inBackground {
            val (range, methods) = CallTargets.hintAt(myFixture.file, end)!!
            range to methods.map { CallTargets.body(it)!! }
        }
        ExpandedCalls.expand(myFixture.editor, range, bodies, 0)
        assertEquals(listOf("val c: Int", "    get() = a + 1"), renderers().single().visibleText())
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

    /** 힌트가 가리키는 호출된 이름 위치(마우스 오버 강조용). 합친 체인은 체인 안의 프로젝트 함수 이름 모두. */
    fun testHintNamesPointAtCalledNames() {
        myFixture.addFileToProject("demo/B.java", chainClass)
        myFixture.configureByText("Main.java", "package demo;\nclass Main {\n    void run(B b) { b.foo().name().trim(); }\n}\n")
        val text = myFixture.editor.document.text
        val end = text.indexOf("trim()") + "trim()".length
        val hint = CallTargets.hintAt(myFixture.file, end)!!
        assertEquals(listOf("foo", "name"), hint.names.map { it.substring(text) })
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
        // 구현체 검색은 "find" 를 누를 때만 한다.
        assertEquals(listOf("int size();", InlineCallBundle.message("body.implementations")), renderer.visibleText())
        val root = renderer.roots.single()
        val find = root.body.calls.single()
        assertTrue(renderer.expand(root, find, inBackground { find.targets.mapNotNull { it.element?.let(find::load) } }))
        assertEquals("\t" + InlineCallBundle.message("body.no.implementations"), renderer.visibleText().last())
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

        // 펼친 직후에는 검색하지 않고 "▶ find" 만 있다.
        assertEquals(listOf("double area();", InlineCallBundle.message("body.implementations")), renderer.visibleText())
        val find = root.body.calls.single()
        assertTrue(renderer.expand(root, find, inBackground { find.targets.mapNotNull { it.element?.let(find::load) } }))
        val list = root.children.getValue(find).single()

        // 본문이 있는 구현체만 나온다(추상 Base 는 빠짐). 검색 순서는 보장되지 않으므로 정렬해서 비교한다.
        val labels = list.body.calls.map { it.label }.sorted().toList()
        assertEquals(listOf("Circle.area()", "Square.area()", "Tri.area()"), labels)
        assertFalse(list.body.hasMoreResults)

        // 구현체 힌트를 펼치면 그 본문이 한 단계 더 아래에 보인다(Kotlin 구현체 포함).
        val tri = list.body.calls.single { it.label == "Tri.area()" }
        assertTrue(renderer.expand(list, tri, inBackground { tri.targets.map { FunctionBody.of(it.element!!)!! } }))
        assertTrue(renderer.visibleText().contains("\t\toverride fun area(): Double = 0.5"))
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

    /** 구현체 목록은 한 페이지씩 찾고, "… more" 로 더 불러온다. */
    fun testImplementationsArePaged() {
        myFixture.configureByText(
            "S.java",
            """
            interface S { int size(); }
            class A implements S { public int size() { return 1; } }
            class B implements S { public int size() { return 2; } }
            class C implements S { public int size() { return 3; } }
            """.trimIndent(),
        )
        val method = (myFixture.file as PsiJavaFile).classes.first().methods.single()
        val firstPage = inBackground { FunctionBody.overridesOf(method, limit = 2)!! }
        assertEquals(2, firstPage.calls.count())
        assertTrue(firstPage.hasMoreResults)

        val renderer = FunctionBodyRenderer(listOf(firstPage), indentPx = 0)
        assertEquals("    " + InlineCallBundle.message("body.more.results"), renderer.visibleText().last())

        val secondPage = inBackground { FunctionBody.overridesOf(method, limit = 4)!! }
        assertTrue(renderer.replaceBody(renderer.roots.single(), secondPage, extraLines = 0))
        assertEquals(3, renderer.roots.single().body.calls.count())
        assertFalse(renderer.visibleText().last().contains(InlineCallBundle.message("body.more.results")))
    }

    /** "… more lines" 는 줄을 더 보여주고, 새로 보이는 줄에도 중첩 힌트가 계산되며, 다시 계산돼도 유지된다. */
    fun testMoreLinesRevealsAnalyzedLines() {
        InlineCallSettings.getInstance().state.maxLines = 2
        myFixture.configureByText(
            "A.java",
            """
            class A {
                int helper(int x) { return x; }
                int caller() {
                    int a = 1;
                    int b = helper(a);
                    return b;
                }
                int v = caller();
            }
            """.trimIndent(),
        )
        val renderer = expandCallEndingWith("caller()")
        val root = renderer.roots.single()
        assertEquals(listOf("int caller() {", "    int a = 1;", InlineCallBundle.message("body.more.lines", 3)), renderer.visibleText())
        assertTrue("calls beyond visible lines are not resolved yet", root.body.calls.none())

        val caller = (myFixture.file as PsiJavaFile).classes.single().findMethodsByName("caller", false).single()
        val more = FunctionBody.of(caller, analyzedLines = renderer.linesPerPage * 2)!!
        assertTrue(renderer.replaceBody(root, more, extraLines = renderer.linesPerPage))
        assertEquals(
            listOf("int caller() {", "    int a = 1;", "    int b = helper(a);", "    return b;", InlineCallBundle.message("body.more.lines", 1)),
            renderer.visibleText(),
        )
        assertEquals(listOf("helper(x)"), root.body.calls.map { it.label }.toList())

        // 원본이 바뀌어 다시 계산돼도 늘린 줄 수는 유지된다.
        val document = myFixture.editor.document
        edit { document.replaceString(document.text.indexOf("int a = 1;"), document.text.indexOf("int a = 1;") + "int a = 1;".length, "int a = 2;") }
        ExpandedCalls.refreshNow(myFixture.editor)
        val refreshed = renderers().single()
        assertEquals(4 + 1, refreshed.visibleText().size)
        assertEquals("    int a = 2;", refreshed.visibleText()[1])
        assertEquals(listOf("helper(x)"), refreshed.roots.single().body.calls.map { it.label }.toList())
    }

    /** 더 보기 줄의 [next N] / [all] 버튼이 각각 클릭되는지(실제 에디터 inlay 위에서 판정) */
    fun testMoreRowHasNextAndAllButtons() {
        InlineCallSettings.getInstance().state.maxLines = 2
        myFixture.configureByText(
            "A.java",
            "class A {\n    int caller() {\n        int a = 1;\n        int b = 2;\n        int c = 3;\n        return a + b + c;\n    }\n    int v = caller();\n}\n",
        )
        val renderer = expandCallEndingWith("caller()")
        val inlay = myFixture.editor.inlayModel.getBlockElementsInRange(0, myFixture.editor.document.textLength).single()
        val bounds = inlay.bounds!!
        val lastRowY = bounds.y + bounds.height - JBUI.scale(2) - myFixture.editor.lineHeight / 2
        val hits = (bounds.x until bounds.x + bounds.width)
            .mapNotNull { x -> renderer.hitTest(inlay, Point(x, lastRowY)) as? BodyHit.More }
            .map { it.action.kind to it.action.all }
            .distinct()
        assertEquals(listOf(MoreKind.LINES to false, MoreKind.LINES to true), hits)
        assertEquals(InlineCallBundle.message("body.more.lines", 4), renderer.visibleText().last())

        // [all]: 남은 줄을 모두 보여준다(리스너와 같은 계산).
        val root = renderer.roots.single()
        val all = FunctionBody.of(root.body.target.element!!, root.body.sourceLineCount)!!
        assertTrue(renderer.replaceBody(root, all, extraLines = root.body.sourceLineCount - renderer.linesPerPage))
        assertEquals(6, renderer.visibleText().size)
        assertEquals("}", renderer.visibleText().last())
    }

    fun testLoadAllImplementations() {
        myFixture.configureByText(
            "S.java",
            """
            interface S { int size(); }
            class A implements S { public int size() { return 1; } }
            class B implements S { public int size() { return 2; } }
            class C implements S { public int size() { return 3; } }
            """.trimIndent(),
        )
        val method = (myFixture.file as PsiJavaFile).classes.first().methods.single()
        assertTrue(inBackground { FunctionBody.overridesOf(method, limit = 1)!! }.hasMoreResults)
        val all = inBackground { FunctionBody.overridesOf(method, FunctionBody.ALL_RESULTS)!! }
        assertEquals(3, all.calls.count())
        assertFalse(all.hasMoreResults)
    }

    /** "▶ find" 는 "searching…" 을 먼저 펼치고 결과로 바꾼다. 검색 중에 접었으면 결과를 버린다. */
    fun testFindShowsSearchingPlaceholder() {
        myFixture.configureByText(
            "S.java",
            """
            interface S { int size(); }
            class A implements S { public int size() { return 1; } }
            """.trimIndent(),
        )
        val method = (myFixture.file as PsiJavaFile).classes.first().methods.single()
        val renderer = FunctionBodyRenderer(listOf(CallTargets.body(method)!!), indentPx = 0)
        val root = renderer.roots.single()
        val find = root.body.calls.single()

        assertTrue(renderer.expand(root, find, listOf(FunctionBody.searching(root.body, find))))
        val pending = root.children.getValue(find)
        assertEquals("\t" + InlineCallBundle.message("body.searching"), renderer.visibleText().last())

        val found = inBackground { find.targets.mapNotNull { it.element?.let(find::load) } }
        assertTrue(renderer.resolvePending(root, find, pending, found))
        assertEquals(listOf("A.size()"), root.children.getValue(find).single().body.calls.map { it.label }.toList())

        // 검색 중에 접었다가(자리표시가 사라짐) 결과가 오면 무시한다.
        assertTrue(renderer.collapse(root, find))
        assertTrue(renderer.expand(root, find, listOf(FunctionBody.searching(root.body, find))))
        val stale = root.children.getValue(find)
        assertTrue(renderer.collapse(root, find))
        assertFalse(renderer.resolvePending(root, find, stale, found))
        assertFalse(root.children.containsKey(find))
    }

    /** 마우스 위치로 어느 본문(최상위/중첩) 위인지 찾는다. 새로 계산돼도 중첩 본문은 호출과의 짝을 유지한다. */
    fun testNodeAtAndParentCallSurviveRefresh() {
        myFixture.configureByText(
            "A.java",
            "class A {\n    int helper(int x) { return x; }\n    int caller() {\n        return helper(1);\n    }\n    int v = caller();\n}\n",
        )
        val renderer = expandCallEndingWith("caller()")
        val root = renderer.roots.single()
        val call = root.body.calls.single()
        assertTrue(renderer.expand(root, call, call.targets.map { FunctionBody.of(it.element!!)!! }))
        val inlay = myFixture.editor.inlayModel.getBlockElementsInRange(0, myFixture.editor.document.textLength).single()
        val bounds = inlay.bounds!!
        val rowY = { row: Int -> bounds.y + JBUI.scale(2) + row * myFixture.editor.lineHeight + 1 }
        assertSame(root, renderer.nodeAt(inlay, Point(bounds.x + 5, rowY(0))))
        assertSame(root.children.getValue(call).single(), renderer.nodeAt(inlay, Point(bounds.x + 5, rowY(2))))

        val document = myFixture.editor.document
        edit { document.insertString(document.text.indexOf("return helper(1);"), "int unused = 0;\n        ") }
        ExpandedCalls.refreshNow(myFixture.editor)
        val refreshed = renderers().single().roots.single()
        val (newCall, children) = refreshed.children.entries.single()
        assertSame(newCall, children.single().parentCall)
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
        assertEquals(false, DeclarativeHint.isExpanded(ourHint()))

        // 화살표는 ▶ 그대로인데 본문만 펼쳐진 어긋난 상태 -> 맞춰서 접는다.
        expandCallEndingWith("helper()")
        HintToggle.syncWithHint(myFixture.editor, ourHint(), toggleIfUnknown = true)
        assertEquals(0, ExpandedCalls.markerCount(myFixture.editor))

        // 화살표 ▶ 이고 본문도 접혀 있으면(토글 안 된 클릭) 아무것도 하지 않는다.
        HintToggle.syncWithHint(myFixture.editor, ourHint(), toggleIfUnknown = true)
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
        HintToggle.syncWithHint(myFixture.editor, ourHint(), toggleIfUnknown = true)
        waitForExpansion()
        assertEquals(1, ExpandedCalls.markerCount(myFixture.editor))
    }

    /** 플랫폼 클릭 처리로 화살표를 실제로 토글한 뒤 본문이 따라오는지. 여백 클릭은 토글도 본문 변화도 없다. */
    fun testPlatformToggleDrivesBody() {
        myFixture.configureByText("A.java", "class A {\n    int helper() { return 1; }\n    int v = helper();\n}\n")
        myFixture.doHighlighting()
        val editor = myFixture.editor

        fun platformClick(x: Int) {
            val hint = ourHint()
            val bounds = hint.bounds!!
            val mouse = MouseEvent(editor.contentComponent, MouseEvent.MOUSE_CLICKED, 0, 0, bounds.x + x, bounds.y + 2, 1, false, MouseEvent.BUTTON1)
            val event = EditorMouseEvent(editor, mouse, EditorMouseEventArea.EDITING_AREA)
            (hint.renderer as DeclarativeInlayRendererBase<*>).handleLeftClick(event, Point(x, 2), false)
            HintToggle.syncWithHint(editor, hint, toggleIfUnknown = true)
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

    /**
     * IDE 를 다시 켜면 플랫폼은 ▼ 상태를 복원하지만 본문(메모리 상태)은 없다.
     * 힌트가 추가/갱신될 때 HintStateListener 가 본문을 다시 펼쳐야 한다(클릭 없이).
     */
    fun testRestoredExpandedArrowReopensBody() {
        myFixture.configureByText("A.java", "class A {\n    int helper() { return 1; }\n    int v = helper();\n}\n")
        myFixture.doHighlighting()
        val editor = myFixture.editor
        // 플랫폼 클릭으로 ▼ 로 만든 뒤, 본문만 사라진 상태(= 재시작 직후)를 만든다.
        val hint = ourHint()
        val bounds = hint.bounds!!
        val middle = hint.widthInPixels / 2
        val mouse = MouseEvent(editor.contentComponent, MouseEvent.MOUSE_CLICKED, 0, 0, bounds.x + middle, bounds.y + 2, 1, false, MouseEvent.BUTTON1)
        (hint.renderer as DeclarativeInlayRendererBase<*>).handleLeftClick(EditorMouseEvent(editor, mouse, EditorMouseEventArea.EDITING_AREA), Point(middle, 2), false)
        assertEquals(true, DeclarativeHint.isExpanded(ourHint()))
        assertEquals(0, ExpandedCalls.markerCount(editor))

        // 힌트 갱신(재시작 시 힌트가 다시 붙는 것과 같은 알림) -> 클릭 없이 본문이 펼쳐진다.
        ourHint().update()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        waitForExpansion()
        assertEquals(1, ExpandedCalls.markerCount(editor))
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

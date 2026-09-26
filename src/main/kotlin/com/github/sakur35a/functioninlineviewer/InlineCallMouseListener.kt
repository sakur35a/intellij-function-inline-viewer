package com.github.sakur35a.functioninlineviewer

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.pom.Navigatable
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.Cursor
import java.awt.Point
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

/**
 * 선언형 힌트는 일반 클릭에 핸들러를 호출하지 않는다(Ctrl+클릭만 핸들러 호출).
 * 그래서 에디터 마우스 리스너로 클릭을 받아 본문 block inlay 를 붙이거나 뗀다.
 * 펼친 본문 안에서의 Cmd(Ctrl)+클릭 이동과 밑줄 표시도 여기서 처리한다.
 */
class InlineCallMouseListener : EditorMouseListener, EditorMouseMotionListener {

    private companion object {
        /**
         * 밑줄/손가락 커서를 표시한 inlay. 플랫폼은 mouse 리스너와 motion 리스너를 별도 인스턴스로 만들기 때문에
         * (mouseExited 는 mouse 쪽, mouseMoved 는 motion 쪽) 두 인스턴스가 같은 상태를 보도록 공유한다. EDT 전용.
         */
        var hoveredInlay: Inlay<*>? = null

        /** setCustomCursor 요청자. 인스턴스가 달라도 같은 커서 설정을 덮어쓰고 지우도록 고정한다. */
        val CURSOR_REQUESTOR = Any()

        /** 이름 강조를 일으킨 대상(호출부 힌트 inlay 또는 최상위 본문 노드)과 그 강조. EDT 전용. */
        var highlightedKey: Any? = null
        var nameHighlighters: List<RangeHighlighter> = emptyList()
    }

    override fun mousePressed(e: EditorMouseEvent) {
        if (e.area != EditorMouseEventArea.EDITING_AREA || !SwingUtilities.isLeftMouseButton(e.mouseEvent)) return
        val inlay = e.inlay ?: return
        val renderer = inlay.renderer as? FunctionBodyRenderer ?: return
        val navigation = isNavigationModifier(e.mouseEvent)
        val hit = renderer.hitTest(inlay, e.mouseEvent.point)
        when {
            navigation && hit is BodyHit.Token && hit.token.isNavigable -> navigate(e.editor, hit.node.body, hit.sourceOffset)
            // 본문 안의 ▶ 힌트를 Cmd+클릭하면 대상 선언(구현체 목록이면 그 구현체)으로 이동한다.
            navigation && hit is BodyHit.Call -> navigateTo(e.editor, hit.call)
            !navigation && hit is BodyHit.Call -> toggleNested(e.editor, inlay, renderer, hit.node, hit.call)
            !navigation && hit is BodyHit.More -> loadMore(e.editor, inlay, renderer, hit.node, hit.action)
            !navigation -> return
        }
        // 본문 아래에 깔린 실제 코드로 이동하거나 캐럿이 움직이지 않도록 이 클릭은 소비한다.
        e.consume()
    }

    override fun mouseMoved(e: EditorMouseEvent) {
        val inlay = e.inlay?.takeIf { e.area == EditorMouseEventArea.EDITING_AREA }
        val renderer = inlay?.renderer as? FunctionBodyRenderer
        val hit = renderer?.hitTest(inlay, e.mouseEvent.point)
        val navigation = isNavigationModifier(e.mouseEvent)
        val token = (hit as? BodyHit.Token)?.token?.takeIf { navigation && it.isNavigable }
        val clickable = token != null || hit is BodyHit.Call || hit is BodyHit.More

        if (hoveredInlay != null && hoveredInlay !== inlay) clearHover(e.editor)
        if (renderer == null) {
            // 플랫폼은 Ctrl 을 눌렀을 때만 손가락 커서를 보여주므로, 토글되는 글자 영역 위에서만 직접 표시한다(여백은 토글되지 않음).
            if (inlay != null && DeclarativeHint.isOurs(inlay)) {
                val bounds = inlay.bounds
                val point = e.mouseEvent.point
                val overText = bounds != null &&
                    DeclarativeHint.isOverText(inlay, Point(point.x - bounds.x, point.y - bounds.y)) == true
                hoveredInlay = inlay
                (e.editor as? EditorEx)?.setCustomCursor(CURSOR_REQUESTOR, if (overText) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else null)
                highlightCalledNames(e.editor, key = inlay, offset = inlay.offset)
            }
            return
        }
        if (renderer.hovered !== token) {
            renderer.hovered = token
            inlay.repaint()
        }
        // 펼친 본문 위: 최상위 본문이면 에디터의 호출된 이름을, 중첩 본문이면 바깥 본문의 호출된 이름을 강조한다.
        val node = renderer.nodeAt(inlay, e.mouseEvent.point)
        if (renderer.hoveredNode !== node) {
            renderer.hoveredNode = node
            inlay.repaint()
        }
        if (node != null && node.depth == 0) {
            val roots = renderer.roots
            highlightCalledNames(e.editor, key = node, offset = inlay.offset, nameIndex = roots.indexOf(node), nameCount = roots.size)
        } else {
            clearNameHighlight()
        }
        hoveredInlay = inlay
        (e.editor as? EditorEx)?.setCustomCursor(CURSOR_REQUESTOR, if (clickable) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else null)
    }

    /** 본문 안의 ▶ 힌트: 펼쳐져 있으면 접고, 아니면 대상 본문을 백그라운드에서 읽어 펼친다. */
    private fun toggleNested(editor: Editor, inlay: Inlay<*>, renderer: FunctionBodyRenderer, node: BodyNode, call: BodyCall) {
        if (renderer.collapse(node, call)) {
            inlay.update()
            return
        }
        val project = editor.project ?: return
        // 구현체/재정의 검색은 처음에 느릴 수 있어 "searching…" 을 먼저 펼쳐 둔다.
        val pending = if (call.searchesOverrides && renderer.expand(node, call, listOf(FunctionBody.searching(node.body, call)))) {
            inlay.update()
            node.children[call]
        } else {
            null
        }
        val start = System.nanoTime()
        ReadAction.nonBlocking<List<FunctionBody>> { call.targets.mapNotNull { it.element?.let(call::load) } }
            .inSmartMode(project)
            .expireWith(project)
            .expireWhen { !inlay.isValid }
            .finishOnUiThread(ModalityState.defaultModalityState()) { bodies ->
                val changed = if (pending != null) renderer.resolvePending(node, call, pending, bodies) else renderer.expand(node, call, bodies)
                if (changed) inlay.update()
                Perf.since("expand.nested", start, "label=${call.label} depth=${node.depth + 1}")
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    /**
     * "… more lines" 는 최대 줄 수만큼 더(또는 전체), "… more" 는 구현체/재정의를 한 페이지 더(또는 전체) 불러온다.
     * 새로 보이는 줄의 색/중첩 힌트도 계산해야 하므로 백그라운드에서 본문을 다시 만든다.
     */
    private fun loadMore(editor: Editor, inlay: Inlay<*>, renderer: FunctionBodyRenderer, node: BodyNode, action: MoreAction) {
        val project = editor.project ?: return
        val body = node.body
        val page = renderer.linesPerPage
        val extraLines = when {
            action.kind != MoreKind.LINES -> node.extraLines
            action.all -> maxOf(body.sourceLineCount - page, node.extraLines)
            else -> node.extraLines + page
        }
        val start = System.nanoTime()
        ReadAction.nonBlocking<FunctionBody?> {
            val target = body.target.element ?: return@nonBlocking null
            when (action.kind) {
                MoreKind.LINES -> FunctionBody.of(target, page + extraLines)
                MoreKind.RESULTS -> FunctionBody.overridesOf(
                    target,
                    if (action.all) FunctionBody.ALL_RESULTS else body.resultLimit + FunctionBody.RESULT_PAGE,
                )
            }
        }
            .inSmartMode(project)
            .expireWith(project)
            .expireWhen { !inlay.isValid }
            .finishOnUiThread(ModalityState.defaultModalityState()) { newBody ->
                if (newBody != null && renderer.replaceBody(node, newBody, extraLines)) inlay.update()
                Perf.since("more", start, "kind=${action.kind} all=${action.all}")
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    override fun mouseExited(e: EditorMouseEvent) {
        clearHover(e.editor)
    }

    /**
     * 짝이 되는 호출된 이름을 에디터에서 강조한다(색: Color Scheme 의 "Called name").
     * 에디터 코드 줄의 힌트는 색을 바꿀 수 없어서(선언형 힌트 API) 힌트에 마우스를 올리거나, 최상위 펼친 본문에
     * 마우스를 올렸을 때 쓴다. 이름 위치는 resolve 가 필요하므로 백그라운드에서 구한다. [key] 가 같으면 다시 구하지 않는다.
     */
    private fun highlightCalledNames(editor: Editor, key: Any, offset: Int, nameIndex: Int? = null, nameCount: Int = 0) {
        if (highlightedKey === key) return
        clearNameHighlight()
        highlightedKey = key
        val project = editor.project ?: return
        ReadAction.nonBlocking<List<TextRange>> {
            val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return@nonBlocking emptyList()
            val names = CallTargets.hintAt(psiFile, offset)?.names.orEmpty()
            // 합친 체인을 펼친 본문(최상위가 여러 개)이면 그 본문에 해당하는 이름만.
            if (nameIndex != null && nameCount > 1 && names.size == nameCount) listOf(names[nameIndex]) else names
        }
            .inSmartMode(project)
            .expireWith(project)
            .expireWhen { highlightedKey !== key || editor.isDisposed }
            .finishOnUiThread(ModalityState.defaultModalityState()) { names ->
                if (highlightedKey !== key) return@finishOnUiThread
                val length = editor.document.textLength
                nameHighlighters = names.filter { it.endOffset <= length }.map {
                    editor.markupModel.addRangeHighlighter(
                        InlineCallColors.CALLED_NAME, it.startOffset, it.endOffset,
                        HighlighterLayer.SELECTION - 1, HighlighterTargetArea.EXACT_RANGE,
                    )
                }
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun clearNameHighlight() {
        highlightedKey = null
        nameHighlighters.forEach { if (it.isValid) it.dispose() }
        nameHighlighters = emptyList()
    }

    private fun clearHover(editor: Editor) {
        clearNameHighlight()
        val inlay = hoveredInlay ?: return
        hoveredInlay = null
        (inlay.renderer as? FunctionBodyRenderer)?.let {
            it.hovered = null
            it.hoveredNode = null
        }
        if (inlay.isValid) inlay.repaint()
        (editor as? EditorEx)?.setCustomCursor(CURSOR_REQUESTOR, null)
    }

    private fun isNavigationModifier(event: MouseEvent): Boolean =
        if (SystemInfo.isMac) event.isMetaDown else event.isControlDown

    private fun navigate(editor: Editor, body: FunctionBody, offset: Int) {
        navigate(editor) { project -> CallTargets.navigationTarget(project, body, offset) }
    }

    private fun navigateTo(editor: Editor, call: BodyCall) {
        navigate(editor) { call.targets.firstNotNullOfOrNull { it.element }?.let(CallTargets::descriptorOf) }
    }

    private fun navigate(editor: Editor, target: (Project) -> Navigatable?) {
        val project = editor.project ?: return
        val start = System.nanoTime()
        ReadAction.nonBlocking<Navigatable?> { target(project) }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.defaultModalityState()) { target ->
                Perf.since("navigate", start)
                if (target != null && target.canNavigate()) target.navigate(true)
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    override fun mouseClicked(e: EditorMouseEvent) {
        // 플랫폼 리스너가 ▶/▼ 를 토글할 수 있도록 이벤트를 consume 하지 않는다.
        if (e.area != EditorMouseEventArea.EDITING_AREA) return
        if (!SwingUtilities.isLeftMouseButton(e.mouseEvent)) return
        val inlay = e.inlay ?: return
        if (!DeclarativeHint.isOurs(inlay)) return
        val editor = e.editor
        // 플랫폼은 글자 영역을 일반 클릭했을 때만 토글한다(여백 클릭, Cmd/Ctrl+클릭은 토글하지 않음).
        // 토글된 힌트는 갱신되므로 HintStateListener 가 본문을 맞춘다. 리스너 실행 순서는 보장되지 않으므로
        // 여기서도 클릭 처리가 모두 끝난 뒤 한 번 더 맞춘다(같은 상태면 아무것도 하지 않는다).
        ApplicationManager.getApplication().invokeLater(
            { HintToggle.syncWithHint(editor, inlay, toggleIfUnknown = true) },
            { editor.isDisposed || !inlay.isValid },
        )
    }
}

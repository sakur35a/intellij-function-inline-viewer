package com.github.ljk0071.inlinecall

import com.intellij.codeInsight.hints.declarative.impl.inlayRenderer.DeclarativeInlayRendererBase
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.TextRange
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiDocumentManager
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.Cursor
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

/**
 * 선언형 힌트는 일반 클릭 시 ▶/▼ 표시만 바꾸고 핸들러를 호출하지 않는다(Ctrl+클릭만 핸들러 호출).
 * 그래서 에디터 마우스 리스너로 같은 클릭을 받아 본문 block inlay 를 붙이거나 뗀다.
 * 펼친 본문 안에서의 Cmd(Ctrl)+클릭 이동과 밑줄 표시도 여기서 처리한다.
 */
class InlineCallMouseListener : EditorMouseListener, EditorMouseMotionListener {

    /** 밑줄이 그려진 본문 inlay (motion 리스너 인스턴스에서만 쓰인다) */
    private var hoveredInlay: Inlay<*>? = null

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
        val clickable = token != null || hit is BodyHit.Call

        if (hoveredInlay != null && hoveredInlay !== inlay) clearHover(e.editor)
        if (renderer == null) return
        if (renderer.hovered !== token) {
            renderer.hovered = token
            inlay.repaint()
        }
        hoveredInlay = if (clickable) inlay else null
        (e.editor as? EditorEx)?.setCustomCursor(this, if (clickable) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else null)
    }

    /** 본문 안의 ▶ 힌트: 펼쳐져 있으면 접고, 아니면 대상 본문을 백그라운드에서 읽어 펼친다. */
    private fun toggleNested(editor: Editor, inlay: Inlay<*>, renderer: FunctionBodyRenderer, node: BodyNode, call: BodyCall) {
        if (renderer.collapse(node, call)) {
            inlay.update()
            return
        }
        val project = editor.project ?: return
        val start = System.nanoTime()
        ReadAction.nonBlocking<List<FunctionBody>> { call.targets.mapNotNull { it.element?.let(call::load) } }
            .inSmartMode(project)
            .expireWith(project)
            .expireWhen { !inlay.isValid }
            .finishOnUiThread(ModalityState.defaultModalityState()) { bodies ->
                if (renderer.expand(node, call, bodies)) inlay.update()
                Perf.since("expand.nested", start, "label=${call.label} depth=${node.depth + 1}")
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    override fun mouseExited(e: EditorMouseEvent) {
        clearHover(e.editor)
    }

    private fun clearHover(editor: Editor) {
        val inlay = hoveredInlay ?: return
        hoveredInlay = null
        (inlay.renderer as? FunctionBodyRenderer)?.hovered = null
        if (inlay.isValid) inlay.repaint()
        (editor as? EditorEx)?.setCustomCursor(this, null)
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
        if (!isOurHint(inlay)) return
        toggle(e.editor, inlay.offset)
    }

    /**
     * 클릭된 inlay 가 이 플러그인의 힌트인지 확인한다.
     * providerId 를 공개 API 로 얻을 방법이 없어 내부 API 를 여기서만 사용한다.
     */
    private fun isOurHint(inlay: Inlay<*>): Boolean {
        if (inlay.placement != Inlay.Placement.INLINE) return false
        val renderer = inlay.renderer as? DeclarativeInlayRendererBase<*> ?: return false
        return renderer.providerId == InlineCallHintsProvider.PROVIDER_ID
    }

    private fun toggle(editor: Editor, callEndOffset: Int) {
        if (ExpandedCalls.collapse(editor, callEndOffset)) return
        val project = editor.project ?: return

        // resolve 는 EDT 를 막지 않도록 백그라운드 읽기 작업으로 수행한다. 인덱싱 중이면 끝날 때까지 기다린다.
        val start = System.nanoTime()
        ReadAction.nonBlocking<Expansion?> { findExpansion(editor, callEndOffset) }
            .inSmartMode(project)
            .withDocumentsCommitted(project)
            .expireWith(project)
            .expireWhen { editor.isDisposed }
            .finishOnUiThread(ModalityState.defaultModalityState()) { expansion ->
                Perf.since("expand", start, "bodies=${expansion?.bodies?.size ?: 0}")
                if (expansion == null || ExpandedCalls.isExpanded(editor, callEndOffset)) return@finishOnUiThread
                ExpandedCalls.expand(editor, expansion.callRange, expansion.bodies, indentPx(editor, expansion.callRange.startOffset))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private class Expansion(val callRange: TextRange, val bodies: List<FunctionBody>)

    private fun findExpansion(editor: Editor, callEndOffset: Int): Expansion? {
        val project = editor.project ?: return null
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return null
        val (range, methods) = CallTargets.hintAt(psiFile, callEndOffset) ?: return null
        return Expansion(range, methods.mapNotNull(CallTargets::body).ifEmpty { return null })
    }

    /** 호출부가 있는 줄의 들여쓰기 위치에 본문을 맞춘다. */
    private fun indentPx(editor: Editor, offset: Int): Int {
        val document = editor.document
        val line = document.getLineNumber(offset)
        val lineStart = document.getLineStartOffset(line)
        val text = document.charsSequence
        var firstNonWs = lineStart
        while (firstNonWs < document.getLineEndOffset(line) && text[firstNonWs].isWhitespace()) firstNonWs++
        return editor.offsetToXY(firstNonWs).x - editor.offsetToXY(lineStart).x
    }
}

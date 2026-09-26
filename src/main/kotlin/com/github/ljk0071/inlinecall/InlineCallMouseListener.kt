package com.github.ljk0071.inlinecall

import com.intellij.codeInsight.hints.declarative.impl.inlayRenderer.DeclarativeInlayRendererBase
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.util.concurrency.AppExecutorUtil
import javax.swing.SwingUtilities

/**
 * 선언형 힌트는 일반 클릭 시 ▶/▼ 표시만 바꾸고 핸들러를 호출하지 않는다(Ctrl+클릭만 핸들러 호출).
 * 그래서 에디터 마우스 리스너로 같은 클릭을 받아 본문 block inlay 를 붙이거나 뗀다.
 */
class InlineCallMouseListener : EditorMouseListener {

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

        // resolve 는 EDT 를 막지 않도록 백그라운드 읽기 작업으로 수행한다.
        ReadAction.nonBlocking<Expansion?> { findExpansion(editor, callEndOffset) }
            .withDocumentsCommitted(project)
            .expireWith(project)
            .expireWhen { editor.isDisposed }
            .finishOnUiThread(ModalityState.defaultModalityState()) { expansion ->
                if (expansion == null || ExpandedCalls.isExpanded(editor, callEndOffset)) return@finishOnUiThread
                ExpandedCalls.expand(editor, expansion.callRange, expansion.lines, indentPx(editor, expansion.callRange.startOffset))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private class Expansion(val callRange: TextRange, val lines: List<String>)

    private fun findExpansion(editor: Editor, callEndOffset: Int): Expansion? {
        val project = editor.project ?: return null
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return null
        if (callEndOffset <= 0) return null
        // 힌트는 호출식 끝에 붙으므로, 끝 오프셋이 같은 부모들 중 호출식을 찾는다.
        var element = psiFile.findElementAt(callEndOffset - 1)
        while (element != null && element.textRange.endOffset == callEndOffset) {
            val call = CallTargets.toCall(element)
            if (call != null) {
                val method = CallTargets.resolveProjectMethod(call) ?: return null
                val lines = CallTargets.bodyLines(method) ?: return null
                return Expansion(element.textRange, lines)
            }
            element = element.parent
        }
        return null
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

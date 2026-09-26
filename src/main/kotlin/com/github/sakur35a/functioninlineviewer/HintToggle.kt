package com.github.sakur35a.functioninlineviewer

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.util.concurrency.AppExecutorUtil

/**
 * 호출부 힌트의 화살표(▶/▼)와 그 아래 본문을 맞춘다. ▶/▼ 는 플랫폼 토글이 관리하고(IDE 를 다시 켜도 유지된다),
 * 본문은 화면의 화살표를 따라간다. 클릭([InlineCallMouseListener])과 힌트 추가/갱신([HintStateListener])에서 호출된다.
 */
object HintToggle {

    /** 본문을 계산 중인 호출 끝 오프셋. 같은 호출을 중복으로 계산하지 않게 한다. EDT 전용. */
    private val PENDING = Key.create<MutableSet<Int>>("inline.call.pending")

    /**
     * EDT 전용. 힌트에 보이는 화살표에 맞춰 본문을 펼치거나 접는다.
     * 화살표를 읽을 수 없으면(IDE 버전 변경 등) [toggleIfUnknown] 일 때만 토글한다(클릭에서만).
     */
    fun syncWithHint(editor: Editor, inlay: Inlay<*>, toggleIfUnknown: Boolean) {
        if (editor.isDisposed || !inlay.isValid) return
        val offset = inlay.offset
        val shown = DeclarativeHint.isExpanded(inlay)
        if (shown == null) {
            if (toggleIfUnknown) toggle(editor, offset)
            return
        }
        if (shown == ExpandedCalls.isExpanded(editor, offset)) return
        if (shown) expand(editor, offset) { DeclarativeHint.isExpanded(inlay) != false } else ExpandedCalls.collapse(editor, offset)
    }

    /** 화살표와 본문이 어긋났는지(맞출 일이 있는지) 싸게 확인한다. */
    fun needsSync(editor: Editor, inlay: Inlay<*>): Boolean {
        val shown = DeclarativeHint.isExpanded(inlay) ?: return false
        if (shown && editor.getUserData(PENDING)?.contains(inlay.offset) == true) return false
        return shown != ExpandedCalls.isExpanded(editor, inlay.offset)
    }

    private fun toggle(editor: Editor, callEndOffset: Int) {
        if (!ExpandedCalls.collapse(editor, callEndOffset)) expand(editor, callEndOffset) { true }
    }

    /** [stillWanted] 는 본문 계산이 끝났을 때 사용자가 그 사이 다시 접지 않았는지 확인한다. */
    private fun expand(editor: Editor, callEndOffset: Int, stillWanted: () -> Boolean) {
        val project = editor.project ?: return
        val pending = editor.getUserData(PENDING) ?: HashSet<Int>().also { editor.putUserData(PENDING, it) }
        if (!pending.add(callEndOffset)) return

        // resolve 는 EDT 를 막지 않도록 백그라운드 읽기 작업으로 수행한다. 인덱싱 중이면 끝날 때까지 기다린다.
        val start = System.nanoTime()
        ReadAction.nonBlocking<Expansion?> { findExpansion(editor, callEndOffset) }
            .inSmartMode(project)
            .withDocumentsCommitted(project)
            .expireWith(project)
            .expireWhen { editor.isDisposed }
            .finishOnUiThread(ModalityState.defaultModalityState()) { expansion ->
                Perf.since("expand", start, "bodies=${expansion?.bodies?.size ?: 0}")
                if (expansion == null || ExpandedCalls.isExpanded(editor, callEndOffset) || !stillWanted()) return@finishOnUiThread
                ExpandedCalls.expand(editor, expansion.callRange, expansion.bodies, indentPx(editor, expansion.callRange.startOffset))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
            .onProcessed { editor.getUserData(PENDING)?.remove(callEndOffset) }
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

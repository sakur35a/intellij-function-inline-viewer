package com.github.sakur35a.functioninlineviewer

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.util.concurrency.AppExecutorUtil

/**
 * 호출부 힌트를 클릭하면([InlineCallMouseListener]) 그 아래 본문을 펼치거나 접는다.
 * 힌트의 화살표(▶/▼)는 본문 상태를 따라 다시 그려진다([CallHints.refreshArrow]).
 */
object HintToggle {

    /** 본문을 계산 중인 호출 끝 오프셋. 같은 호출을 중복으로 계산하지 않게 한다. EDT 전용. */
    private val PENDING = Key.create<MutableSet<Int>>("inline.call.pending")

    /** 계산 중에 다시 눌러 펼치기를 취소한 호출 끝 오프셋. EDT 전용. */
    private val CANCELLED = Key.create<MutableSet<Int>>("inline.call.cancelled")

    /** EDT 전용. 펼쳐져 있으면 접고, 아니면 펼친다. 본문을 계산하는 중에 다시 누르면 펼치기를 취소한다. */
    fun toggle(editor: Editor, callEndOffset: Int) {
        if (editor.getUserData(PENDING)?.contains(callEndOffset) == true) {
            cancelled(editor).add(callEndOffset)
            return
        }
        if (!ExpandedCalls.collapse(editor, callEndOffset)) expand(editor, callEndOffset)
    }

    /** EDT 전용. 대상 본문을 백그라운드에서 읽어 펼친다. */
    fun expand(editor: Editor, callEndOffset: Int) {
        val project = editor.project ?: return
        val pending = editor.getUserData(PENDING) ?: HashSet<Int>().also { editor.putUserData(PENDING, it) }
        if (!pending.add(callEndOffset)) return
        cancelled(editor).remove(callEndOffset)

        // resolve 는 EDT 를 막지 않도록 백그라운드 읽기 작업으로 수행한다. 인덱싱 중이면 끝날 때까지 기다린다.
        val start = System.nanoTime()
        ReadAction.nonBlocking<Expansion?> { findExpansion(editor, callEndOffset) }
            .inSmartMode(project)
            .withDocumentsCommitted(project)
            .expireWith(project)
            .expireWhen { editor.isDisposed }
            .finishOnUiThread(ModalityState.defaultModalityState()) { expansion ->
                Perf.since("expand", start, "bodies=${expansion?.bodies?.size ?: 0}")
                // 계산하는 사이 다시 눌러 취소했으면 펼치지 않는다.
                if (cancelled(editor).remove(callEndOffset)) return@finishOnUiThread
                if (expansion == null || ExpandedCalls.isExpanded(editor, callEndOffset)) return@finishOnUiThread
                ExpandedCalls.expand(editor, expansion.callRange, expansion.bodies, indentPx(editor, expansion.callRange.startOffset))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
            .onProcessed { editor.getUserData(PENDING)?.remove(callEndOffset) }
    }

    private fun cancelled(editor: Editor): MutableSet<Int> =
        editor.getUserData(CANCELLED) ?: HashSet<Int>().also { editor.putUserData(CANCELLED, it) }

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

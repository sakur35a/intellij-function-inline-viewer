package com.github.sakur35a.functioninlineviewer

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.ConcurrentHashMap

/**
 * 에디터별로 "펼쳐진 호출부"와 그 아래 붙은 block inlay 를 관리한다.
 * 호출부 위치는 RangeMarker 로 들고 있어서 편집해도 따라 움직인다.
 */
object ExpandedCalls {

    private val KEY = Key.create<EditorExpansions>("inline.call.expanded")

    /** 백그라운드(힌트 수집)에서도 호출된다. */
    fun isExpanded(editor: Editor, callEndOffset: Int): Boolean =
        editor.getUserData(KEY)?.isExpanded(callEndOffset) == true

    /** EDT 전용. 펼쳐져 있었으면 접고 true 를 돌려준다. */
    fun collapse(editor: Editor, callEndOffset: Int): Boolean =
        editor.getUserData(KEY)?.collapse(callEndOffset) == true

    /** EDT 전용. 에디터의 펼친 본문을 모두 접는다(힌트 화살표도 ▶ 로 다시 그려진다). 접은 본문이 있었으면 true. */
    fun collapseAll(editor: Editor): Boolean {
        val expansions = editor.getUserData(KEY)
        val any = expansions != null && expansions.size > 0
        expansions?.collapseAll()
        return any
    }

    /** EDT 전용. */
    fun expand(editor: Editor, callRange: TextRange, bodies: List<FunctionBody>, indentPx: Int) {
        if (bodies.isEmpty() || editor.isDisposed) return
        val expansions = editor.getUserData(KEY) ?: EditorExpansions(editor).also {
            editor.putUserData(KEY, it)
            // 에디터가 닫히면 marker/inlay/리스너를 모두 해제한다(문서에 붙은 RangeMarker 누수 방지).
            EditorUtil.disposeWithEditor(editor, it)
        }
        expansions.expand(callRange, bodies, indentPx)
    }

    /**
     * EDT 전용. 설정이 바뀌면 열린 모든 에디터의 펼친 본문에 반영한다.
     * [recompute] 면(체인 합치기 변경) 본문 안의 힌트 규칙이 바뀌므로 원본이 그대로여도 다시 계산한다.
     */
    fun settingsChanged(recompute: Boolean) {
        for (editor in EditorFactory.getInstance().allEditors) editor.getUserData(KEY)?.applySettings(recompute)
    }

    /** 테스트용: 대기 중인 재계산을 즉시 동기로 수행한다. */
    @TestOnly
    fun refreshNow(editor: Editor, force: Boolean = false) {
        editor.getUserData(KEY)?.refreshNow(force)
    }

    @TestOnly
    fun markerCount(editor: Editor): Int = editor.getUserData(KEY)?.size ?: 0

    private class EditorExpansions(private val editor: Editor) : Disposable {

        private val entries = ConcurrentHashMap<RangeMarker, Inlay<FunctionBodyRenderer>>()

        /** 펼친 호출부의 힌트 라벨. 저장/복원([SavedExpansions])할 때 같은 호출인지 확인하는 데 쓴다. */
        private val labels = ConcurrentHashMap<RangeMarker, String>()

        val size: Int get() = entries.size

        init {
            // 호출부가 있는 문서 또는 펼친 본문의 원본 문서가 바뀌면 다시 계산한다.
            EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    if (isRelevant(event.document)) scheduleRefresh(force = false)
                }
            }, this)
        }

        fun isExpanded(callEndOffset: Int): Boolean =
            entries.entries.any { (marker, inlay) -> marker.isValid && inlay.isValid && marker.endOffset == callEndOffset }

        fun collapse(callEndOffset: Int): Boolean {
            val marker = entries.keys.firstOrNull { it.isValid && it.endOffset == callEndOffset } ?: return false
            remove(marker)
            return true
        }

        fun collapseAll() {
            entries.keys.toList().forEach(::remove)
        }

        fun expand(callRange: TextRange, bodies: List<FunctionBody>, indentPx: Int) {
            val options = InlineCallSettings.getInstance().state
            val inlay = editor.inlayModel.addBlockElement(
                callRange.endOffset,
                /* relatesToPrecedingText = */ true,
                /* showAbove = */ false,
                /* priority = */ 0,
                FunctionBodyRenderer(bodies, indentPx, options.maxLines, options.maxDepth),
            ) ?: return
            val marker = editor.document.createRangeMarker(callRange)
            entries[marker] = inlay
            CallHints.labelAt(editor, callRange.endOffset)?.let { labels[marker] = it }
            CallHints.refreshArrow(editor, callRange.endOffset)
            save()
        }

        fun applySettings(recompute: Boolean) {
            val options = InlineCallSettings.getInstance().state
            for (inlay in entries.values) {
                if (!inlay.isValid) continue
                inlay.renderer.updateLimits(options.maxLines, options.maxDepth)
                inlay.update()
            }
            if (recompute) scheduleRefresh(force = true)
        }

        private fun remove(marker: RangeMarker, save: Boolean = true) {
            val offset = marker.takeIf { it.isValid }?.endOffset
            entries.remove(marker)?.let { if (it.isValid) Disposer.dispose(it) }
            labels.remove(marker)
            marker.dispose()
            if (offset != null) CallHints.refreshArrow(editor, offset)
            if (save) save()
        }

        /** 펼친 호출부를 [SavedExpansions] 에 기록해 파일을 다시 열거나 IDE 를 다시 켰을 때 복원되게 한다. */
        private fun save() {
            val project = editor.project ?: return
            if (project.isDisposed) return
            val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
            val expansions = entries.keys.filter { it.isValid }.mapNotNull { marker ->
                labels[marker]?.let { CallHints.Info(marker.endOffset, it) }
            }
            SavedExpansions.getInstance(project).set(file.url, expansions)
        }

        private fun isRelevant(document: Document): Boolean {
            if (entries.isEmpty()) return false
            if (document == editor.document) return true
            val file = FileDocumentManager.getInstance().getFile(document) ?: return false
            return entries.values.any { it.isValid && file in it.renderer.files() }
        }

        // ---- 다시 계산 ----

        private class Pending(val marker: RangeMarker, val inlay: Inlay<FunctionBodyRenderer>, val version: Int, val snapshot: List<BodySnapshot>)

        /** null 이면 더 이상 프로젝트 함수 호출이 아니므로 접는다. */
        private class Result(val pending: Pending, val roots: List<BodyNode>?)

        private fun pending(): List<Pending> =
            entries.map { (marker, inlay) -> Pending(marker, inlay, inlay.renderer.version, inlay.renderer.snapshot()) }

        /** 강제 재계산 요청이 뒤이은 일반 재계산에 취소돼도 사라지지 않도록, 반영될 때까지 유지한다. EDT 전용. */
        private var forceRequested = false

        private fun scheduleRefresh(force: Boolean) {
            val project = editor.project ?: return
            val pending = pending()
            if (pending.isEmpty()) return
            if (force) forceRequested = true
            val forceNow = forceRequested
            // 입력이 이어지면 이전 계산은 취소되고(coalesce) 마지막 것만 반영된다.
            ReadAction.nonBlocking<List<Result>> {
                Perf.measure("refresh", detail = { "expansions=${pending.size} force=$forceNow" }) { pending.map { compute(it, forceNow) } }
            }
                .inSmartMode(project)
                .withDocumentsCommitted(project)
                .expireWith(this)
                .coalesceBy(this)
                .finishOnUiThread(ModalityState.defaultModalityState()) { results ->
                    if (forceNow) forceRequested = false
                    apply(results)
                }
                .submit(AppExecutorUtil.getAppExecutorService())
        }

        fun refreshNow(force: Boolean) {
            val project = editor.project ?: return
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            apply(runReadActionBlocking { pending().map { compute(it, force) } })
        }

        /** [force] 면 원본이 그대로인 본문도 재사용하지 않고 다시 만든다. */
        private fun compute(pending: Pending, force: Boolean): Result {
            val marker = pending.marker
            if (!marker.isValid || marker.startOffset >= marker.endOffset) return Result(pending, null)
            val project = editor.project ?: return Result(pending, null)
            val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return Result(pending, null)
            // 호출부를 다시 resolve 해서 대상이 바뀌었거나 사라졌는지 확인한다.
            val (_, methods) = CallTargets.hintAt(psiFile, marker.endOffset) ?: return Result(pending, null)
            val roots = methods.mapIndexedNotNull { index, method ->
                rebuild(CallTargets.declarationOf(method), pending.snapshot.getOrNull(index), depth = 0, force)
            }
            return Result(pending, roots.ifEmpty { null })
        }

        /** [old] 의 펼침 상태를 유지한 채 [declaration] 본문을 다시 만든다. 원본이 그대로면 기존 본문을 재사용한다. */
        private fun rebuild(
            declaration: PsiElement,
            old: BodySnapshot?,
            depth: Int,
            force: Boolean,
            load: (PsiElement) -> FunctionBody? = FunctionBody::of,
        ): BodyNode? {
            val reusable = old?.body?.takeIf { !force && it.isUpToDate() && it.target.element == declaration }
            // "… more" 로 늘린 줄 수/목록 개수는 다시 계산해도 유지한다.
            val extraLines = old?.extraLines ?: 0
            val body = reusable
                ?: old?.let { FunctionBody.rebuildLike(it.body, declaration, InlineCallSettings.getInstance().state.maxLines + extraLines) }
                ?: load(declaration)
                ?: return null
            val node = BodyNode(body, depth, extraLines)
            if (old == null) return node
            for (call in body.calls) {
                val oldChildren = old.children[call.key] ?: continue
                val children = call.targets.mapIndexedNotNull { index, pointer ->
                    pointer.element?.let { rebuild(it, oldChildren.getOrNull(index), depth + 1, force, call::load) }
                }
                children.forEach { it.parentCall = call }
                if (children.isNotEmpty()) node.children[call] = children
            }
            return node
        }

        private fun apply(results: List<Result>) {
            for (result in results) {
                val pending = result.pending
                val inlay = entries[pending.marker] ?: continue
                // 계산하는 사이 사용자가 펼치거나 접었으면 그 상태를 우선한다(다음 변경 때 다시 계산된다).
                if (inlay !== pending.inlay || inlay.renderer.version != pending.version) continue
                val roots = result.roots
                // 자동으로 접히는 건 호출이 지워졌거나 더 이상 프로젝트 함수 호출이 아닐 때라 호출부 힌트도 함께 사라진다.
                if (roots == null || !inlay.isValid) {
                    remove(pending.marker)
                } else {
                    inlay.renderer.replaceRoots(roots)
                    inlay.update()
                    // 대상이 바뀌면(이름 변경 등) 힌트 라벨도 바뀐다.
                    CallHints.labelAt(editor, pending.marker.endOffset)?.let { labels[pending.marker] = it }
                }
            }
            // 편집으로 호출부 위치가 옮겨졌을 수 있으니 저장해 둔 위치도 갱신한다.
            save()
        }

        override fun dispose() {
            // 에디터가 닫힐 때: 마지막 위치를 저장하고 나서 정리한다(다시 열면 복원된다).
            save()
            entries.keys.toList().forEach { remove(it, save = false) }
            editor.putUserData(KEY, null)
        }
    }
}

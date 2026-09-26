package com.github.sakur35a.functioninlineviewer

import com.intellij.codeHighlighting.TextEditorHighlightingPass
import com.intellij.codeHighlighting.TextEditorHighlightingPassFactory
import com.intellij.codeHighlighting.TextEditorHighlightingPassFactoryRegistrar
import com.intellij.codeHighlighting.TextEditorHighlightingPassRegistrar
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiFile
import com.intellij.psi.SyntaxTraverser

/**
 * 호출부 힌트. 하이라이팅 패스([Factory])가 파일을 열거나 고칠 때마다 백그라운드에서 힌트를 모으고,
 * 에디터에 [CallHintRenderer] inline inlay 로 붙인다(바뀐 것만 더하고 뺀다).
 * 클릭은 [InlineCallMouseListener] 가 받아 [HintToggle] 로 본문을 펼치거나 접는다.
 */
object CallHints {

    /** 힌트 하나: 붙는 오프셋(호출식/참조 끝)과 라벨 */
    data class Info(val offset: Int, val label: String)

    /** 이 에디터에서 저장해 둔 펼침 상태를 이미 복원했는지 */
    private val RESTORED = Key.create<Boolean>("inline.call.hints.restored")

    /** [file] 의 모든 호출부 힌트. 읽기 작업 안에서 호출. */
    fun collect(file: PsiFile): List<Info> {
        val hints = ArrayList<Info>()
        for (element in SyntaxTraverser.psiTraverser(file)) {
            ProgressManager.checkCanceled()
            val hint = Perf.measure("collect.call", thresholdMs = 2.0, detail = { "file=${file.name} offset=${element.textRange.endOffset}" }) {
                CallTargets.hintFor(element)
            } ?: continue
            hints += Info(hint.range.endOffset, hint.label)
        }
        return hints
    }

    /** 이 플러그인의 호출부 힌트들 */
    fun hints(editor: Editor): List<Inlay<out CallHintRenderer>> =
        editor.inlayModel.getInlineElementsInRange(0, editor.document.textLength, CallHintRenderer::class.java)

    /**
     * EDT 전용. 에디터의 힌트를 [hints] 로 맞춘다. 그대로인 힌트는 남겨 두고 바뀐 것만 더하고 뺀다.
     * 에디터를 처음 채울 때는 IDE 를 다시 켜기 전에 펼쳐 두었던 본문([SavedExpansions])을 다시 펼친다.
     */
    fun apply(editor: Editor, hints: List<Info>) {
        if (editor.isDisposed) return
        val wanted = hints.toMutableSet()
        for (inlay in hints(editor)) {
            if (!wanted.remove(Info(inlay.offset, inlay.renderer.label))) inlay.dispose()
        }
        for (info in wanted) {
            if (info.offset <= editor.document.textLength) {
                editor.inlayModel.addInlineElement(info.offset, /* relatesToPrecedingText = */ true, CallHintRenderer(info.label))
            }
        }
        restoreSaved(editor, hints)
    }

    private fun restoreSaved(editor: Editor, hints: List<Info>) {
        if (editor.getUserData(RESTORED) == true) return
        editor.putUserData(RESTORED, true)
        val project = editor.project ?: return
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val saved = SavedExpansions.getInstance(project).get(file.url).toSet()
        for (info in hints) {
            if (info in saved && !ExpandedCalls.isExpanded(editor, info.offset)) HintToggle.expand(editor, info.offset)
        }
    }

    /** EDT 전용. [callEndOffset] 의 힌트 화살표를 본문 상태에 맞춰 다시 그린다. */
    fun refreshArrow(editor: Editor, callEndOffset: Int) {
        if (editor.isDisposed || callEndOffset > editor.document.textLength) return
        for (inlay in editor.inlayModel.getInlineElementsInRange(callEndOffset, callEndOffset, CallHintRenderer::class.java)) {
            inlay.update()
        }
    }

    /** [callEndOffset] 의 힌트 라벨(없으면 null). EDT 전용. */
    fun labelAt(editor: Editor, callEndOffset: Int): String? {
        if (callEndOffset > editor.document.textLength) return null
        return editor.inlayModel.getInlineElementsInRange(callEndOffset, callEndOffset, CallHintRenderer::class.java)
            .firstOrNull()?.renderer?.label
    }

    /** 에디터마다 힌트를 모으는 하이라이팅 패스를 등록한다. */
    class Factory : TextEditorHighlightingPassFactory, TextEditorHighlightingPassFactoryRegistrar {
        override fun registerHighlightingPassFactory(registrar: TextEditorHighlightingPassRegistrar, project: Project) {
            registrar.registerTextEditorHighlightingPass(this, null, null, false, -1)
        }

        override fun createHighlightingPass(file: PsiFile, editor: Editor): TextEditorHighlightingPass? =
            if (editor.project == null) null else Pass(file, editor)
    }

    private class Pass(private val file: PsiFile, private val editor: Editor) :
        TextEditorHighlightingPass(file.project, editor.document, false) {

        private var hints: List<Info> = emptyList()

        override fun doCollectInformation(progress: ProgressIndicator) {
            // 설정에서 힌트를 끄면 빈 목록으로 맞춰 이미 붙은 힌트도 지운다.
            hints = if (InlineCallSettings.getInstance().state.showHints) collect(file) else emptyList()
        }

        override fun doApplyInformationToEditor() {
            apply(editor, hints)
        }
    }
}

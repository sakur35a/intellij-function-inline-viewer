package com.github.ljk0071.inlinecall

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.InlayModel
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.util.Disposer

/**
 * 호출부 힌트가 추가/갱신될 때 화살표와 본문을 맞춘다.
 * - 플랫폼은 ▶/▼ 상태를 IDE 를 다시 켜도 복원하므로, ▼ 로 복원된 힌트의 본문을 다시 펼친다.
 * - 클릭으로 토글된 힌트도 갱신되므로 같은 경로로 맞춰진다.
 */
class HintStateListener : EditorFactoryListener {

    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        if (editor.project == null) return
        val disposable = Disposer.newDisposable("inline call hint state")
        EditorUtil.disposeWithEditor(editor, disposable)
        editor.inlayModel.addListener(object : InlayModel.Listener {
            override fun onAdded(inlay: Inlay<*>) = check(editor, inlay)
            override fun onUpdated(inlay: Inlay<*>, changeFlags: Int) = check(editor, inlay)
        }, disposable)
    }

    private fun check(editor: Editor, inlay: Inlay<*>) {
        if (!DeclarativeHint.isOurs(inlay) || !HintToggle.needsSync(editor, inlay)) return
        // inlay 모델 변경 알림 도중에는 inlay 를 추가/삭제하지 않도록 다음 이벤트에서 맞춘다.
        ApplicationManager.getApplication().invokeLater(
            { HintToggle.syncWithHint(editor, inlay, toggleIfUnknown = false) },
            { editor.isDisposed || !inlay.isValid },
        )
    }
}

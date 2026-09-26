package com.github.ljk0071.inlinecall

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.UserDataHolderEx
import java.util.concurrent.ConcurrentHashMap

/**
 * 에디터별로 "펼쳐진 호출부"와 그 아래 붙은 block inlay 를 관리한다.
 * 호출부 위치는 RangeMarker 로 들고 있어서 편집해도 따라 움직인다.
 */
object ExpandedCalls {

    private val KEY = Key.create<MutableMap<RangeMarker, Inlay<*>>>("inline.call.expanded")

    private fun entries(editor: Editor): MutableMap<RangeMarker, Inlay<*>> =
        editor.getUserData(KEY)
            ?: (editor as UserDataHolderEx).putUserDataIfAbsent(KEY, ConcurrentHashMap())

    /** 백그라운드(힌트 수집)에서도 호출된다. */
    fun isExpanded(editor: Editor, callEndOffset: Int): Boolean =
        editor.getUserData(KEY)?.entries?.any { (marker, inlay) ->
            marker.isValid && inlay.isValid && marker.endOffset == callEndOffset
        } == true

    /** EDT 전용. 펼쳐져 있었으면 접고 true 를 돌려준다. */
    fun collapse(editor: Editor, callEndOffset: Int): Boolean {
        val map = entries(editor)
        cleanUp(map)
        val found = map.keys.firstOrNull { it.endOffset == callEndOffset } ?: return false
        map.remove(found)?.let { Disposer.dispose(it) }
        found.dispose()
        return true
    }

    /** EDT 전용. */
    fun expand(editor: Editor, callRange: TextRange, lines: List<String>, indentPx: Int) {
        val map = entries(editor)
        cleanUp(map)
        val inlay = editor.inlayModel.addBlockElement(
            callRange.endOffset,
            /* relatesToPrecedingText = */ true,
            /* showAbove = */ false,
            /* priority = */ 0,
            FunctionBodyRenderer(lines, indentPx),
        ) ?: return
        val marker = editor.document.createRangeMarker(callRange)
        map[marker] = inlay
    }

    /** 편집으로 호출부가 지워지는 등 무효해진 항목 정리 */
    private fun cleanUp(map: MutableMap<RangeMarker, Inlay<*>>) {
        val iterator = map.entries.iterator()
        while (iterator.hasNext()) {
            val (marker, inlay) = iterator.next()
            if (!marker.isValid || !inlay.isValid) {
                if (inlay.isValid) Disposer.dispose(inlay)
                marker.dispose()
                iterator.remove()
            }
        }
    }
}

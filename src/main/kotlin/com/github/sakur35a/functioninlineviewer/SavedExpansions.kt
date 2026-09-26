package com.github.sakur35a.functioninlineviewer

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * 파일별로 펼쳐 둔 호출부(힌트 오프셋 + 라벨). IDE 를 다시 켜거나 파일을 다시 열면 [CallHints.apply] 가 다시 펼친다.
 * 펼치거나 접을 때와 에디터가 닫힐 때 [ExpandedCalls] 가 갱신한다. 워크스페이스(개인) 설정에 저장한다.
 */
@Service(Service.Level.PROJECT)
@State(name = "FunctionInlineViewerExpansions", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class SavedExpansions : SimplePersistentStateComponent<SavedExpansions.Entries>(Entries()) {

    class Entries : BaseState() {
        /** 파일 URL -> "오프셋:라벨" 목록 */
        var files by map<String, MutableList<String>>()

        /** 맵 안의 값을 바꾼 것은 자동으로 감지되지 않으므로 저장되도록 알린다. */
        fun changed() = incrementModificationCount()
    }

    fun get(fileUrl: String): List<CallHints.Info> =
        state.files[fileUrl].orEmpty().mapNotNull { entry ->
            val separator = entry.indexOf(':')
            val offset = entry.substring(0, maxOf(separator, 0)).toIntOrNull() ?: return@mapNotNull null
            CallHints.Info(offset, entry.substring(separator + 1))
        }

    fun set(fileUrl: String, expansions: List<CallHints.Info>) {
        val entries = expansions.map { "${it.offset}:${it.label}" }
        if (entries.isEmpty()) {
            if (state.files.remove(fileUrl) != null) state.changed()
        } else if (state.files[fileUrl] != entries) {
            state.files[fileUrl] = entries.toMutableList()
            state.changed()
        }
    }

    companion object {
        fun getInstance(project: Project): SavedExpansions = project.service()
    }
}

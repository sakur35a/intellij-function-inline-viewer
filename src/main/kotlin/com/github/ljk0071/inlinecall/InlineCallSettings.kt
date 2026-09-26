package com.github.ljk0071.inlinecall

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/** 앱 전체 설정. 값은 새로 펼치는 본문부터 적용된다. */
@Service(Service.Level.APP)
@State(name = "InlineCallBodySettings", storages = [Storage("inlineCallBody.xml")])
class InlineCallSettings : SimplePersistentStateComponent<InlineCallSettings.Options>(Options()) {

    class Options : BaseState() {
        /** 본문 하나에서 보여줄 최대 줄 수. 넘으면 "… (N more lines)" 로 줄인다. */
        var maxLines by property(FunctionBodyRenderer.DEFAULT_MAX_LINES)

        /** 본문 안에서 다시 펼칠 수 있는 최대 깊이. 0 이면 중첩 펼침을 끈다. */
        var maxDepth by property(FunctionBodyRenderer.DEFAULT_MAX_DEPTH)

        /** 같은 줄의 체인 호출 `a.foo().bar()` 을 끝에 힌트 하나로 합친다. */
        var mergeChains by property(true)
    }

    companion object {
        fun getInstance(): InlineCallSettings = service()
    }
}

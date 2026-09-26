package com.github.sakur35a.functioninlineviewer

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel

/** Settings > Editor > Function Inline Viewer */
class InlineCallConfigurable : BoundConfigurable(InlineCallBundle.message("settings.display.name")) {

    /** 저장한 뒤 이미 펼쳐진 본문과 힌트에 바로 반영한다. */
    override fun apply() {
        val options = InlineCallSettings.getInstance().state
        val mergeChainsBefore = options.mergeChains
        val maxLinesBefore = options.maxLines
        super.apply()
        val mergeChainsChanged = options.mergeChains != mergeChainsBefore
        // 의미 분석 색은 최대 줄 수까지만 계산해 두므로 늘어나면 다시 계산한다.
        ExpandedCalls.settingsChanged(recompute = mergeChainsChanged || options.maxLines > maxLinesBefore)
        // 체인 합치기가 바뀌면 호출부 힌트 위치/라벨도 바뀌므로 힌트를 다시 수집한다.
        if (mergeChainsChanged) {
            for (project in ProjectManager.getInstance().openProjects) {
                DaemonCodeAnalyzer.getInstance(project).restart("inline call settings changed")
            }
        }
    }

    override fun createPanel(): DialogPanel {
        val options = InlineCallSettings.getInstance().state
        return panel {
            row(InlineCallBundle.message("settings.max.lines")) {
                spinner(1..1000).bindIntValue(options::maxLines)
            }
            row(InlineCallBundle.message("settings.max.depth")) {
                spinner(0..20).bindIntValue(options::maxDepth)
                    .comment(InlineCallBundle.message("settings.max.depth.comment"))
            }
            row {
                checkBox(InlineCallBundle.message("settings.merge.chains")).bindSelected(options::mergeChains)
                    .comment(InlineCallBundle.message("settings.merge.chains.comment"))
            }
        }
    }
}

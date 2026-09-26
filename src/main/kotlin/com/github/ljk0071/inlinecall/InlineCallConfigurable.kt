package com.github.ljk0071.inlinecall

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel

/** Settings > Editor > Inline Call Body */
class InlineCallConfigurable : BoundConfigurable(InlineCallBundle.message("settings.display.name")) {

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

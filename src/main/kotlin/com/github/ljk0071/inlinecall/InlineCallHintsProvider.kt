package com.github.ljk0071.inlinecall

import com.intellij.codeInsight.hints.declarative.HintColorKind
import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * 호출부 뒤에 "▶ 함수명(파라미터)" 회색 힌트를 붙인다.
 * 본문 표시는 [InlineCallMouseListener] 가 클릭 시 block inlay 로 처리한다.
 */
class InlineCallHintsProvider : InlayHintsProvider {

    companion object {
        const val PROVIDER_ID: String = "inline.call.body"

        /** 선언형 힌트의 text() 한 조각 최대 길이 */
        private const val MAX_TEXT_LENGTH = 30

        private val FORMAT = HintFormat.default.withColorKind(HintColorKind.TextWithoutBackground)
    }

    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector = Collector(editor)

    private class Collector(private val editor: Editor) : SharedBypassCollector {
        override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
            val call = CallTargets.toCall(element) ?: return
            val methods = Perf.measure("collect.call", thresholdMs = 2.0, detail = { "file=${element.containingFile.name} offset=${element.textRange.endOffset}" }) {
                CallTargets.hintTargets(call)
            } ?: return
            val label = CallTargets.labelOf(methods)
            val offset = element.textRange.endOffset
            val expanded = ExpandedCalls.isExpanded(editor, offset)

            sink.addPresentation(
                InlineInlayPosition(offset, relatedToPrevious = true),
                tooltip = "Click to show/hide the body of ${methods.joinToString { it.name }}",
                hintFormat = FORMAT,
            ) {
                // 플랫폼 토글(collapsibleList/toggleButton)은 사용자가 한 번 누른 상태를 계속 유지하고
                // provider 가 넘기는 상태를 무시해서, 자동으로 접힐 때 등 실제 펼침 상태와 어긋났다. 화살표는 직접 정한다.
                text(if (expanded) "▼ " else "▶ ")
                label.chunked(MAX_TEXT_LENGTH).forEach { text(it) }
            }
        }
    }
}

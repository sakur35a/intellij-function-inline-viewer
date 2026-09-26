package com.github.ljk0071.inlinecall

import com.intellij.codeInsight.hints.declarative.CollapseState
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
                // ▶/▼ 전환은 플랫폼 토글이 즉시 처리한다. 여기서 정하는 상태는 힌트가 처음 만들어질 때만 쓰이고
                // (플랫폼은 사용자가 바꾼 상태를 유지한다), 본문은 InlineCallMouseListener 가 화면의 화살표에 맞춘다.
                collapsibleList(
                    state = if (expanded) CollapseState.Expanded else CollapseState.Collapsed,
                    expandedState = {
                        toggleButton {
                            text("▼ ")
                            label.chunked(MAX_TEXT_LENGTH).forEach { text(it) }
                        }
                    },
                    collapsedState = {
                        toggleButton {
                            text("▶ ")
                            label.chunked(MAX_TEXT_LENGTH).forEach { text(it) }
                        }
                    },
                )
            }
        }
    }
}

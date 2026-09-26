package com.github.sakur35a.functioninlineviewer

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.ex.util.EditorUIUtil
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Font
import java.awt.Graphics
import java.awt.Rectangle

/**
 * 호출부 뒤의 "▶ 함수명(파라미터)" 힌트. 화살표는 그 호출의 본문이 펼쳐져 있는지([ExpandedCalls])를 따르므로,
 * 펼치거나 접을 때 [Inlay.update] 로 다시 그린다([CallHints.refreshArrow]).
 */
class CallHintRenderer(val label: String) : EditorCustomElementRenderer {

    /** 지금 보여줄 글자("▶ label" / "▼ label"). EDT 전용. */
    fun text(inlay: Inlay<*>): String =
        (if (ExpandedCalls.isExpanded(inlay.editor, inlay.offset)) "▼ " else "▶ ") + label

    override fun calcWidthInPixels(inlay: Inlay<*>): Int =
        inlay.editor.contentComponent.getFontMetrics(font(inlay.editor)).stringWidth(text(inlay)) + 2 * padding

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        val editor = inlay.editor
        val font = font(editor)
        val metrics = editor.contentComponent.getFontMetrics(font)
        val attributes = editor.colorsScheme.getAttributes(DefaultLanguageHighlighterColors.INLAY_TEXT_WITHOUT_BACKGROUND)
        EditorUIUtil.setupAntialiasing(g)
        g.font = font
        g.color = attributes?.foregroundColor ?: JBColor.GRAY
        // 줄 높이 안에서 글자를 세로 가운데에 맞춘다.
        val baseline = targetRegion.y + (targetRegion.height - metrics.height) / 2 + metrics.ascent
        g.drawString(text(inlay), targetRegion.x + padding, baseline)
    }

    private val padding get() = JBUI.scale(2)

    private fun font(editor: Editor): Font {
        val attributes = editor.colorsScheme.getAttributes(DefaultLanguageHighlighterColors.INLAY_TEXT_WITHOUT_BACKGROUND)
        return editor.colorsScheme.getFont(EditorFontType.forJavaStyle(attributes?.fontType ?: Font.PLAIN))
    }
}

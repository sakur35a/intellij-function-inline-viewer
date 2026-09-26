package com.github.ljk0071.inlinecall

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.ex.util.EditorUIUtil
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics2D
import java.awt.geom.Rectangle2D

/**
 * 호출부 아래에 함수 원문을 회색으로 그리는 block inlay 렌더러.
 */
class FunctionBodyRenderer(
    lines: List<String>,
    private val indentPx: Int,
    maxLines: Int = DEFAULT_MAX_LINES,
) : EditorCustomElementRenderer {

    companion object {
        const val DEFAULT_MAX_LINES = 30
    }

    private val shownLines: List<String> =
        if (lines.size <= maxLines) lines
        else lines.take(maxLines) + "… (${lines.size - maxLines} more lines)"

    private val barWidth get() = JBUI.scale(2)
    private val gap get() = JBUI.scale(8)
    private val verticalPadding get() = JBUI.scale(2)

    private fun font(editor: Editor): Font = editor.colorsScheme.getFont(EditorFontType.ITALIC)

    private fun metrics(editor: Editor): FontMetrics = editor.contentComponent.getFontMetrics(font(editor))

    private fun textColor(editor: Editor): Color =
        editor.colorsScheme.getAttributes(DefaultLanguageHighlighterColors.INLAY_TEXT_WITHOUT_BACKGROUND)?.foregroundColor
            ?: JBColor.GRAY

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val fm = metrics(inlay.editor)
        val textWidth = shownLines.maxOfOrNull { fm.stringWidth(it) } ?: 0
        return indentPx + barWidth + gap + textWidth + gap
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int =
        inlay.editor.lineHeight * shownLines.size + verticalPadding * 2

    override fun paint(inlay: Inlay<*>, g: Graphics2D, targetRegion: Rectangle2D, textAttributes: TextAttributes) {
        val editor = inlay.editor
        val lineHeight = editor.lineHeight
        val fm = metrics(editor)
        val color = textColor(editor)
        val x = targetRegion.x.toInt() + indentPx
        val y = targetRegion.y.toInt()

        EditorUIUtil.setupAntialiasing(g)

        // 왼쪽 세로 막대로 "펼쳐진 본문" 영역임을 표시
        g.color = JBColor(Color(color.red, color.green, color.blue, 90), Color(color.red, color.green, color.blue, 90))
        g.fillRect(x, y + verticalPadding, barWidth, targetRegion.height.toInt() - verticalPadding * 2)

        g.font = font(editor)
        g.color = color
        val baselineShift = (lineHeight - fm.height) / 2 + fm.ascent
        shownLines.forEachIndexed { i, line ->
            g.drawString(line, x + barWidth + gap, y + verticalPadding + i * lineHeight + baselineShift)
        }
    }
}

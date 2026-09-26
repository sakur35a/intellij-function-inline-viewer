package com.github.ljk0071.inlinecall

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.ex.util.EditorUIUtil
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.ColorUtil
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.Point
import java.awt.geom.Rectangle2D

/**
 * 호출부 아래에 함수 원문을 문법 색상과 함께 그리는 block inlay 렌더러.
 * 원본 위치를 알고 있어서 [hitTest] 로 클릭 지점의 원본 오프셋을 구할 수 있다.
 */
class FunctionBodyRenderer(
    val body: FunctionBody,
    private val indentPx: Int,
    maxLines: Int = DEFAULT_MAX_LINES,
) : EditorCustomElementRenderer {

    companion object {
        const val DEFAULT_MAX_LINES = 30
    }

    private val shownLines: List<BodyLine> = body.lines.take(maxLines)
    private val moreText: String? =
        if (body.lines.size > maxLines) "… (${body.lines.size - maxLines} more lines)" else null
    private val rowCount get() = shownLines.size + if (moreText != null) 1 else 0

    /** Cmd 를 누른 채 마우스를 올린 토큰. 밑줄로 표시한다. EDT 에서만 바꾼다. */
    var hovered: BodyToken? = null

    private val barWidth get() = JBUI.scale(2)
    private val gap get() = JBUI.scale(8)
    private val verticalPadding get() = JBUI.scale(2)

    private fun textX(inlayX: Int) = inlayX + indentPx + barWidth + gap

    private fun attributes(editor: Editor, token: BodyToken): TextAttributes? =
        token.keys.fold(null as TextAttributes?) { acc, key ->
            val attrs = editor.colorsScheme.getAttributes(key) ?: return@fold acc
            if (acc == null) attrs else TextAttributes.merge(acc, attrs)
        }

    private fun font(editor: Editor, attrs: TextAttributes?): Font =
        editor.colorsScheme.getFont(EditorFontType.forJavaStyle(attrs?.fontType ?: Font.PLAIN))

    private fun width(editor: Editor, token: BodyToken): Int =
        editor.contentComponent.getFontMetrics(font(editor, attributes(editor, token))).stringWidth(token.text)

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val editor = inlay.editor
        val plain = editor.contentComponent.getFontMetrics(font(editor, null))
        val textWidth = maxOf(
            shownLines.maxOfOrNull { line -> line.tokens.sumOf { width(editor, it) } } ?: 0,
            moreText?.let { plain.stringWidth(it) } ?: 0,
        )
        return indentPx + barWidth + gap + textWidth + gap
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int =
        inlay.editor.lineHeight * rowCount + verticalPadding * 2

    override fun paint(inlay: Inlay<*>, g: Graphics2D, targetRegion: Rectangle2D, textAttributes: TextAttributes) {
        val editor = inlay.editor
        val lineHeight = editor.lineHeight
        val scheme = editor.colorsScheme
        val x = targetRegion.x.toInt()
        val y = targetRegion.y.toInt()
        val plainMetrics = editor.contentComponent.getFontMetrics(font(editor, null))
        val baselineShift = (lineHeight - plainMetrics.height) / 2 + plainMetrics.ascent

        EditorUIUtil.setupAntialiasing(g)

        // 왼쪽 세로 막대로 "펼쳐진 본문" 영역임을 표시
        g.color = ColorUtil.withAlpha(scheme.defaultForeground, 0.35)
        g.fillRect(x + indentPx, y + verticalPadding, barWidth, targetRegion.height.toInt() - verticalPadding * 2)

        shownLines.forEachIndexed { row, line ->
            val baseline = y + verticalPadding + row * lineHeight + baselineShift
            var tx = textX(x)
            for (token in line.tokens) {
                val attrs = attributes(editor, token)
                val font = font(editor, attrs)
                val width = editor.contentComponent.getFontMetrics(font).stringWidth(token.text)
                val color: Color = attrs?.foregroundColor ?: scheme.defaultForeground
                g.font = font
                g.color = color
                g.drawString(token.text, tx, baseline)
                if (token === hovered) {
                    g.fillRect(tx, baseline + JBUI.scale(1), width, JBUI.scale(1))
                }
                tx += width
            }
        }
        moreText?.let {
            g.font = font(editor, null)
            g.color = ColorUtil.withAlpha(scheme.defaultForeground, 0.5)
            g.drawString(it, textX(x), y + verticalPadding + shownLines.size * lineHeight + baselineShift)
        }
    }

    /** [point](에디터 content 좌표) 아래의 토큰과 원본 오프셋. 없으면 null. */
    fun hitTest(inlay: Inlay<*>, point: Point): Pair<BodyToken, Int>? {
        val editor = inlay.editor
        val bounds = inlay.bounds ?: return null
        val row = (point.y - bounds.y - verticalPadding).floorDiv(editor.lineHeight)
        val line = shownLines.getOrNull(row) ?: return null
        var tx = textX(bounds.x)
        if (point.x < tx) return null
        for (token in line.tokens) {
            val fm = editor.contentComponent.getFontMetrics(font(editor, attributes(editor, token)))
            val width = fm.stringWidth(token.text)
            if (point.x < tx + width) {
                var index = 0
                while (index < token.text.length && tx + fm.stringWidth(token.text.substring(0, index + 1)) <= point.x) index++
                return token to token.sourceOffsetAt(index)
            }
            tx += width
        }
        return null
    }
}

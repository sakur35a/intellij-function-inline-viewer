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
import com.intellij.util.ui.UIUtil
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.geom.Path2D
import kotlin.math.roundToInt

/**
 * 호출부 뒤의 "▶ 함수명(파라미터)" 힌트. 화살표는 그 호출의 본문이 펼쳐져 있는지([ExpandedCalls])를 따르므로,
 * 펼치거나 접을 때 [Inlay.update] 로 다시 그린다([CallHints.refreshArrow]).
 */
class CallHintRenderer(val label: String) : EditorCustomElementRenderer {

    /** 힌트를 글로 나타낸 것("▶ label" / "▼ label"). 화면에는 화살표를 [FoldArrow] 로 그린다. EDT 전용. */
    fun text(inlay: Inlay<*>): String = (if (expanded(inlay)) "▼ " else "▶ ") + label

    private fun expanded(inlay: Inlay<*>) = ExpandedCalls.isExpanded(inlay.editor, inlay.offset)

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val editor = inlay.editor
        return FoldArrow.width(metrics(editor, null)) + metrics(editor, label).stringWidth(label) + 2 * padding
    }

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        val editor = inlay.editor
        val arrowMetrics = metrics(editor, null)
        val labelFont = font(editor, label)
        val attributes = editor.colorsScheme.getAttributes(DefaultLanguageHighlighterColors.INLAY_TEXT_WITHOUT_BACKGROUND)
        EditorUIUtil.setupAntialiasing(g)
        g.color = attributes?.foregroundColor ?: JBColor.GRAY
        // 줄 높이 안에서 글자를 세로 가운데에 맞춘다.
        val baseline = targetRegion.y + (targetRegion.height - arrowMetrics.height) / 2 + arrowMetrics.ascent
        val x = targetRegion.x + padding
        FoldArrow.paint(g as Graphics2D, arrowMetrics, x, baseline, expanded(inlay))
        g.font = labelFont
        g.drawString(label, x + FoldArrow.width(arrowMetrics), baseline)
    }

    private val padding get() = JBUI.scale(2)

    private fun metrics(editor: Editor, text: String?): FontMetrics = editor.contentComponent.getFontMetrics(font(editor, text))

    /** 힌트 폰트. [text] 를 주면 그 글자가 없는 폰트일 때 대체 글꼴을 섞는다(drawString 은 에디터와 달리 대체하지 않는다). */
    private fun font(editor: Editor, text: String?): Font {
        val attributes = editor.colorsScheme.getAttributes(DefaultLanguageHighlighterColors.INLAY_TEXT_WITHOUT_BACKGROUND)
        val font = editor.colorsScheme.getFont(EditorFontType.forJavaStyle(attributes?.fontType ?: Font.PLAIN))
        return if (text == null) font else UIUtil.getFontWithFallbackIfNeeded(font, text)
    }
}

/**
 * 펼침(▼)/접힘(▶) 화살표. 글자로 그리면 에디터 폰트(색 구성표마다 다를 수 있다)에 그 글리프가 없을 때 "?" 나 네모로 깨진다.
 * ▶ 는 있고 ▼ 만 없는 폰트면 한 번 펼친 뒤부터 깨진다. 그래서 폰트와 상관없이 삼각형을 직접 그린다.
 */
internal object FoldArrow {
    /** 화살표와 그 뒤 공백을 합친 폭 */
    fun width(metrics: FontMetrics): Int = size(metrics) + metrics.charWidth(' ')

    /** 현재 색으로 [x] 부터 그린다. 세로는 [baseline] 위 대문자 높이의 가운데에 맞춘다. */
    fun paint(g: Graphics2D, metrics: FontMetrics, x: Int, baseline: Int, expanded: Boolean) {
        val size = size(metrics).toDouble()
        val left = x.toDouble()
        val centerY = baseline - metrics.ascent * 0.35
        val path = Path2D.Double()
        if (expanded) {
            val half = size * 0.4
            path.moveTo(left, centerY - half)
            path.lineTo(left + size, centerY - half)
            path.lineTo(left + size / 2, centerY + half)
        } else {
            val inset = size * 0.1
            path.moveTo(left + inset, centerY - size / 2)
            path.lineTo(left + size - inset, centerY)
            path.lineTo(left + inset, centerY + size / 2)
        }
        path.closePath()
        val antialiasing = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING) ?: RenderingHints.VALUE_ANTIALIAS_DEFAULT
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.fill(path)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, antialiasing)
    }

    private fun size(metrics: FontMetrics): Int = (metrics.ascent * 0.55).roundToInt().coerceAtLeast(1)
}

package com.github.ljk0071.inlinecall

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.ex.util.EditorUIUtil
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Font
import java.awt.Graphics2D
import java.awt.Point
import java.awt.geom.Rectangle2D

/** 펼쳐진 본문 하나. [children] 은 본문 안에서 다시 펼친 호출들(체인이면 호출 하나에 본문 여러 개). */
class BodyNode(val body: FunctionBody, val depth: Int) {
    val children = LinkedHashMap<BodyCall, List<BodyNode>>()
}

/** 다시 계산할 때 펼침 상태를 옮기기 위한 스냅샷. [BodyCall.key] 로 새 본문의 호출과 맞춘다. */
class BodySnapshot(val body: FunctionBody, val children: Map<String, List<BodySnapshot>>)

/** 렌더러 안에서 클릭된 대상 */
sealed interface BodyHit {
    class Token(val node: BodyNode, val token: BodyToken, val sourceOffset: Int) : BodyHit
    class Call(val node: BodyNode, val call: BodyCall) : BodyHit
}

/**
 * 호출부 아래에 함수 원문을 문법 색상과 함께 그리는 block inlay 렌더러.
 * 본문 안의 프로젝트 함수 호출에도 ▶ 힌트를 그리고, 펼치면 그 줄 아래에 한 단계 들여서 중첩 본문을 그린다.
 * 원본 위치를 알고 있어서 [hitTest] 로 클릭 지점의 원본 오프셋을 구할 수 있다.
 */
class FunctionBodyRenderer(
    bodies: List<FunctionBody>,
    private val indentPx: Int,
    private var maxLines: Int = DEFAULT_MAX_LINES,
    private var maxDepth: Int = DEFAULT_MAX_DEPTH,
) : EditorCustomElementRenderer {

    companion object {
        const val DEFAULT_MAX_LINES = 30

        /** 최상위 본문이 depth 0. 이 깊이의 본문에는 더 펼칠 힌트를 그리지 않는다. */
        const val DEFAULT_MAX_DEPTH = 4
    }

    /** 최상위 본문들. 합친 체인이면 여러 개. */
    var roots: List<BodyNode> = bodies.map { BodyNode(it, 0) }
        private set

    /** 펼침/접힘/교체마다 증가. 백그라운드 재계산 결과가 그 사이 사용자 조작을 덮어쓰지 않게 한다. */
    var version = 0
        private set

    /** Cmd 를 누른 채 마우스를 올린 토큰. 밑줄로 표시한다. EDT 에서만 바꾼다. */
    var hovered: BodyToken? = null

    /** 한 줄: 본문의 한 줄이거나, 안내 문구(잘린 줄 수 / 본문 없음). [separator] 면 위에 구분선을 긋는다. */
    private class Row(val node: BodyNode, val line: BodyLine?, val moreText: String?, val separator: Boolean = false)

    private sealed interface Piece {
        val x: Int
        val width: Int

        class Text(val token: BodyToken, val attrs: TextAttributes?, override val x: Int, override val width: Int) : Piece
        class Hint(val call: BodyCall, val text: String, override val x: Int, override val width: Int) : Piece
    }

    private var rows: List<Row> = flatten()

    private fun flatten(): List<Row> {
        val result = ArrayList<Row>()
        fun visit(nodes: List<BodyNode>) {
            nodes.forEachIndexed { index, node ->
                val lines = node.body.lines
                lines.take(maxLines).forEachIndexed { lineIndex, line ->
                    result += Row(node, line, null, separator = index > 0 && lineIndex == 0)
                    for (call in line.calls) node.children[call]?.let(::visit)
                }
                if (lines.size > maxLines) result += Row(node, null, "… (${lines.size - maxLines} more lines)")
                if (!node.body.hasBody) result += Row(node, null, InlineCallBundle.message("body.no.body"))
            }
        }
        visit(roots)
        return result
    }

    private fun changed() {
        rows = flatten()
        version++
    }

    /** EDT 전용. 이미 펼쳐져 있었으면 접고 true. 호출 후 inlay.update() 필요. */
    fun collapse(node: BodyNode, call: BodyCall): Boolean {
        node.children.remove(call) ?: return false
        changed()
        return true
    }

    /** EDT 전용. 호출 후 inlay.update() 필요. */
    fun expand(node: BodyNode, call: BodyCall, bodies: List<FunctionBody>): Boolean {
        if (bodies.isEmpty() || node.children.containsKey(call) || !canExpand(node)) return false
        node.children[call] = bodies.map { BodyNode(it, node.depth + 1) }
        changed()
        return true
    }

    /** EDT 전용. 다시 계산한 트리로 바꾼다. 호출 후 inlay.update() 필요. */
    fun replaceRoots(newRoots: List<BodyNode>) {
        hovered = null
        roots = newRoots
        changed()
    }

    /** EDT 전용. 설정이 바뀌면 호출. 새 최대 깊이보다 깊게 펼쳐진 본문은 접는다. 호출 후 inlay.update() 필요. */
    fun updateLimits(maxLines: Int, maxDepth: Int) {
        if (maxLines == this.maxLines && maxDepth == this.maxDepth) return
        this.maxLines = maxLines
        this.maxDepth = maxDepth
        fun prune(node: BodyNode) {
            if (!canExpand(node)) node.children.clear() else node.children.values.forEach { it.forEach(::prune) }
        }
        roots.forEach(::prune)
        changed()
    }

    /** EDT 전용. 현재 펼침 상태 스냅샷. */
    fun snapshot(): List<BodySnapshot> {
        fun snap(node: BodyNode): BodySnapshot =
            BodySnapshot(node.body, node.children.entries.associate { (call, nodes) -> call.key to nodes.map(::snap) })
        return roots.map(::snap)
    }

    /** 보이는 본문들의 원본 파일. 이 파일이 바뀌면 다시 계산한다. */
    fun files(): Set<VirtualFile> {
        val result = HashSet<VirtualFile>()
        fun visit(node: BodyNode) {
            result += node.body.file
            node.children.values.forEach { it.forEach(::visit) }
        }
        roots.forEach(::visit)
        return result
    }

    private fun canExpand(node: BodyNode) = node.depth < maxDepth

    /** 테스트용: 현재 보이는 줄 텍스트(깊이만큼 탭으로 들여쓴다) */
    fun visibleText(): List<String> = rows.map { "\t".repeat(it.node.depth) + (it.line?.text ?: it.moreText) }

    // ---- 레이아웃 ----

    private val barWidth get() = JBUI.scale(2)
    private val gap get() = JBUI.scale(8)
    private val verticalPadding get() = JBUI.scale(2)
    private val hintPadding get() = JBUI.scale(3)

    private fun nestStep(editor: Editor) = plainMetrics(editor).stringWidth("    ")

    private fun barX(editor: Editor, inlayX: Int, depth: Int) = inlayX + indentPx + depth * nestStep(editor)

    private fun textX(editor: Editor, inlayX: Int, depth: Int) = barX(editor, inlayX, depth) + barWidth + gap

    private fun attributes(editor: Editor, token: BodyToken): TextAttributes? =
        token.keys.fold(null as TextAttributes?) { acc, key ->
            val attrs = editor.colorsScheme.getAttributes(key) ?: return@fold acc
            if (acc == null) attrs else TextAttributes.merge(acc, attrs)
        }

    private fun font(editor: Editor, attrs: TextAttributes?): Font =
        editor.colorsScheme.getFont(EditorFontType.forJavaStyle(attrs?.fontType ?: Font.PLAIN))

    private fun plainMetrics(editor: Editor) = editor.contentComponent.getFontMetrics(font(editor, null))

    private fun hintAttributes(editor: Editor): TextAttributes? =
        editor.colorsScheme.getAttributes(DefaultLanguageHighlighterColors.INLAY_DEFAULT)

    private fun layout(editor: Editor, row: Row, inlayX: Int): List<Piece> {
        val line = row.line ?: return emptyList()
        val pieces = ArrayList<Piece>()
        var x = textX(editor, inlayX, row.node.depth)
        val showHints = canExpand(row.node)
        line.tokens.forEachIndexed { index, token ->
            val attrs = attributes(editor, token)
            val width = editor.contentComponent.getFontMetrics(font(editor, attrs)).stringWidth(token.text)
            pieces += Piece.Text(token, attrs, x, width)
            x += width
            if (!showHints) return@forEachIndexed
            for (call in line.calls) {
                if (call.afterToken != index) continue
                val arrow = if (row.node.children.containsKey(call)) "▼ " else "▶ "
                val text = arrow + call.label
                x += hintPadding
                val hintWidth = plainMetrics(editor).stringWidth(text) + hintPadding * 2
                pieces += Piece.Hint(call, text, x, hintWidth)
                x += hintWidth
            }
        }
        return pieces
    }

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val editor = inlay.editor
        val right = rows.maxOfOrNull { row ->
            row.moreText?.let { textX(editor, 0, row.node.depth) + plainMetrics(editor).stringWidth(it) }
                ?: layout(editor, row, 0).lastOrNull()?.let { it.x + it.width }
                ?: 0
        } ?: 0
        return right + gap
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int =
        inlay.editor.lineHeight * rows.size + verticalPadding * 2

    override fun paint(inlay: Inlay<*>, g: Graphics2D, targetRegion: Rectangle2D, textAttributes: TextAttributes) {
        val editor = inlay.editor
        val lineHeight = editor.lineHeight
        val scheme = editor.colorsScheme
        val x = targetRegion.x.toInt()
        val y = targetRegion.y.toInt() + verticalPadding
        val plain = plainMetrics(editor)
        val baselineShift = (lineHeight - plain.height) / 2 + plain.ascent
        val hintAttrs = hintAttributes(editor)

        EditorUIUtil.setupAntialiasing(g)

        rows.forEachIndexed { index, row ->
            val rowY = y + index * lineHeight
            val baseline = rowY + baselineShift

            // 깊이마다 왼쪽 세로 막대로 "펼쳐진 본문" 영역임을 표시
            g.color = ColorUtil.withAlpha(scheme.defaultForeground, 0.35)
            for (depth in 0..row.node.depth) g.fillRect(barX(editor, x, depth), rowY, barWidth, lineHeight)
            // 합친 체인의 본문 사이 구분선
            if (row.separator) {
                val left = barX(editor, x, row.node.depth)
                g.fillRect(left, rowY, (calcWidthInPixels(inlay) - (left - x)).coerceAtLeast(0), JBUI.scale(1))
            }

            row.moreText?.let {
                g.font = font(editor, null)
                g.color = ColorUtil.withAlpha(scheme.defaultForeground, 0.5)
                g.drawString(it, textX(editor, x, row.node.depth), baseline)
            }
            for (piece in layout(editor, row, x)) {
                when (piece) {
                    is Piece.Text -> {
                        g.font = font(editor, piece.attrs)
                        g.color = piece.attrs?.foregroundColor ?: scheme.defaultForeground
                        g.drawString(piece.token.text, piece.x, baseline)
                        if (piece.token === hovered) g.fillRect(piece.x, baseline + JBUI.scale(1), piece.width, JBUI.scale(1))
                    }
                    is Piece.Hint -> {
                        hintAttrs?.backgroundColor?.let {
                            g.color = it
                            val arc = JBUI.scale(6)
                            g.fillRoundRect(piece.x, rowY + JBUI.scale(1), piece.width, lineHeight - JBUI.scale(2), arc, arc)
                        }
                        g.font = font(editor, null)
                        g.color = hintAttrs?.foregroundColor ?: JBColor.GRAY
                        g.drawString(piece.text, piece.x + hintPadding, baseline)
                    }
                }
            }
        }
    }

    /** [point](에디터 content 좌표) 아래의 토큰 또는 호출 힌트. 없으면 null. */
    fun hitTest(inlay: Inlay<*>, point: Point): BodyHit? {
        val editor = inlay.editor
        val bounds = inlay.bounds ?: return null
        val row = rows.getOrNull((point.y - bounds.y - verticalPadding).floorDiv(editor.lineHeight)) ?: return null
        val piece = layout(editor, row, bounds.x).firstOrNull { point.x >= it.x && point.x < it.x + it.width } ?: return null
        return when (piece) {
            is Piece.Hint -> BodyHit.Call(row.node, piece.call)
            is Piece.Text -> {
                val fm = editor.contentComponent.getFontMetrics(font(editor, piece.attrs))
                val text = piece.token.text
                var index = 0
                while (index < text.length && piece.x + fm.stringWidth(text.substring(0, index + 1)) <= point.x) index++
                BodyHit.Token(row.node, piece.token, piece.token.sourceOffsetAt(index))
            }
        }
    }
}
